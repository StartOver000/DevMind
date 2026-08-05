package com.devmind.document;

import com.devmind.ai.AiModelGateway;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.document.chunker.TextChunk;
import com.devmind.document.chunker.TextChunkerFactory;
import com.devmind.document.dto.DocumentTaskResponse;
import com.devmind.document.parser.DocumentParserRegistry;
import com.devmind.retrieval.ChunkRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class DocumentTaskService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaskService.class);

    private final DocumentTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final DocumentParserRegistry parserRegistry;
    private final TextChunkerFactory chunkerFactory;
    private final ChunkRepository chunkRepository;
    private final AiModelGateway modelGateway;
    private final TaskQueue taskQueue;
    private final TaskScheduler taskScheduler;
    private final DevMindProperties properties;
    private final MeterRegistry meterRegistry;

    public DocumentTaskService(
            DocumentTaskRepository taskRepository,
            DocumentRepository documentRepository,
            DocumentParserRegistry parserRegistry,
            TextChunkerFactory chunkerFactory,
            ChunkRepository chunkRepository,
            AiModelGateway modelGateway,
            TaskQueue taskQueue,
            @Qualifier("documentTaskScheduler") TaskScheduler taskScheduler,
            DevMindProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.parserRegistry = parserRegistry;
        this.chunkerFactory = chunkerFactory;
        this.chunkRepository = chunkRepository;
        this.modelGateway = modelGateway;
        this.taskQueue = taskQueue;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public DocumentTaskResponse getTask(Long taskId) {
        DocumentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, "任务不存在"));
        return toResponse(task);
    }

    public DocumentTaskResponse getTaskByDocument(Long documentId) {
        DocumentTask task = taskRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, "文档处理任务不存在"));
        return toResponse(task);
    }

    /**
     * 提交任务到队列（Redis Stream 或内存队列）。
     * 可重复提交：消费端按任务 ID 幂等（claimForProcessing 抢占），多实例不会重复处理。
     */
    public void submitTask(Long taskId) {
        taskQueue.enqueue(taskId);
    }

    void processTask(Long taskId) {
        DocumentTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !"PENDING".equals(task.status())) {
            return;
        }
        if (!taskRepository.claimForProcessing(taskId)) {
            return;
        }

        Document document = documentRepository.findById(task.documentId()).orElse(null);
        if (document == null || "DELETED".equals(document.status())) {
            taskRepository.markFailedPermanent(taskId, "文档不存在或已删除");
            return;
        }

        try {
            documentRepository.updateStatus(document.id(), "PROCESSING", null);
            String filePath = document.filePath().replace('\\', '/');
            byte[] bytes = Files.readAllBytes(Path.of(filePath));
            String text = parserRegistry.parse(document.fileName(), document.fileType(), bytes);
            List<TextChunk> chunks = chunkerFactory.get().chunk(text);
            List<List<Double>> embeddings = modelGateway.embed(
                    chunks.stream().map(TextChunk::content).toList()
            );
            chunkRepository.insertChunks(
                    document.id(),
                    chunks,
                    embeddings,
                    document.metadata() == null ? java.util.Map.of() : document.metadata()
            );
            documentRepository.updateStatus(document.id(), "COMPLETED", null);
            taskRepository.markSucceeded(taskId);
            meterRegistry.counter("devmind.task.succeeded").increment();
            log.info("document task succeeded, taskId={}, documentId={}, chunks={}", taskId, document.id(), chunks.size());
        } catch (Exception ex) {
            handleFailure(taskId, task, document, ex);
        }
    }

    private void handleFailure(Long taskId, DocumentTask task, Document document, Exception ex) {
        String message = truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), 2000);
        int nextRetry = task.retryCount() + 1;
        if (nextRetry < task.maxRetries()) {
            taskRepository.markFailedForRetry(taskId, message);
            log.warn("document task failed, will retry, taskId={}, retry={}", taskId, nextRetry, ex);
            scheduleRetry(taskId);
        } else {
            taskRepository.markFailedPermanent(taskId, message);
            documentRepository.updateStatus(document.id(), "FAILED", message);
            meterRegistry.counter("devmind.task.failed").increment();
            log.error("document task failed permanently, taskId={}, documentId={}", taskId, document.id(), ex);
        }
    }

    private void scheduleRetry(Long taskId) {
        taskScheduler.schedule(
                () -> submitTask(taskId),
                Instant.now().plusMillis(properties.taskRetryDelayMs())
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverTasksOnStartup() {
        // 启动消费（幂等：重复触发只启动一次）
        taskQueue.start(taskId -> processTask(taskId));
        int reset = taskRepository.resetAllProcessingToPending();
        if (reset > 0) {
            log.info("reset {} processing tasks to pending on startup", reset);
        }
        taskRepository.findPendingIds().forEach(this::submitTask);
    }

    @Scheduled(
            fixedDelayString = "${devmind.task-scan-interval-ms:60000}",
            initialDelayString = "${devmind.task-scan-initial-delay-ms:60000}"
    )
    public void scanTimeoutTasks() {
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(properties.taskTimeoutMinutes());
        for (Long taskId : taskRepository.findStuckProcessingIds(before)) {
            taskRepository.resetToPending(taskId);
            submitTask(taskId);
        }
        taskRepository.findPendingIds().forEach(this::submitTask);
    }

    private DocumentTaskResponse toResponse(DocumentTask task) {
        return new DocumentTaskResponse(
                task.id(),
                task.documentId(),
                task.status(),
                task.retryCount(),
                task.maxRetries(),
                task.errorMessage(),
                task.createdTime(),
                task.updatedTime()
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
