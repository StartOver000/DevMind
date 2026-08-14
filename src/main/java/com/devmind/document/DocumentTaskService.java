package com.devmind.document;

import com.devmind.ai.AiModelGateway;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.common.HashUtils;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    /** 保留注入（构造签名稳定）；延迟重试已由 MQ 消息级重投承担 */
    @SuppressWarnings("unused")
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

    void processTask(Long taskId) throws Exception {
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
            // 增量更新：按内容哈希 diff，只重算新增/变更片段，复用未变更片段（embedding 调用大幅减少）
            Set<String> existingHashes = chunkRepository.findHashSetByDocument(document.id());
            List<TextChunk> changedChunks = new ArrayList<>();
            List<String> removedHashes = new ArrayList<>();
            for (TextChunk chunk : chunks) {
                // 防御：content 为 null 的 chunk（解析异常产物）在哈希/embedding 前转空串，避免 NPE 与智谱 400
                String content = chunk.content() == null ? "" : chunk.content();
                String hash = HashUtils.sha256(content);
                if (existingHashes.remove(hash)) {
                    meterRegistry.counter("devmind.document.reused").increment();
                } else {
                    changedChunks.add(new TextChunk(chunk.index(), content, chunk.heading()));
                }
            }
            removedHashes.addAll(existingHashes);
            List<List<Double>> embeddings = changedChunks.isEmpty()
                    ? List.of()
                    : modelGateway.embed(changedChunks.stream()
                            .map(TextChunk::content)
                            // 防御：智谱 embedding 不接受 input 数组含 null（HTTP 400 code 1210），null 统一转空串
                            .map(content -> content == null ? "" : content)
                            .toList());
            chunkRepository.updateChunksIncremental(
                    document.id(),
                    changedChunks,
                    embeddings,
                    removedHashes,
                    document.metadata() == null ? java.util.Map.of() : document.metadata()
            );
            meterRegistry.counter("devmind.document.rechunked").increment(changedChunks.size());
            documentRepository.updateStatus(document.id(), "COMPLETED", null);
            taskRepository.markSucceeded(taskId);
            meterRegistry.counter("devmind.task.succeeded").increment();
            log.info("document task succeeded, taskId={}, documentId={}, chunks={}, changed={}, removed={}",
                    taskId, document.id(), chunks.size(), changedChunks.size(), removedHashes.size());
        } catch (Exception ex) {
            handleFailure(taskId, task, document, ex);
        }
    }

    /**
     * 处理失败：DB 记录重试状态，重试路径向上抛异常给 MQ 层做消息级重投（attempt+1，3 次进 DLQ）；
     * 达到 DB 层 max_retries 上限则标记终态 FAILED（不抛）。
     */
    private void handleFailure(Long taskId, DocumentTask task, Document document, Exception ex) throws Exception {
        String message = truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), 2000);
        int nextRetry = task.retryCount() + 1;
        if (nextRetry < task.maxRetries()) {
            taskRepository.markFailedForRetry(taskId, message);
            log.warn("document task failed, will retry via MQ, taskId={}, retry={}", taskId, nextRetry, ex);
            throw ex;
        }
        taskRepository.markFailedPermanent(taskId, message);
        documentRepository.updateStatus(document.id(), "FAILED", message);
        meterRegistry.counter("devmind.task.failed").increment();
        log.error("document task failed permanently, taskId={}, documentId={}", taskId, document.id(), ex);
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

    /**
     * 死信队列扫描：把消息级重试超限进 DLQ 的任务标记为 DEAD 终态，
     * 终止无限循环重投（治"队列积压只增不减"），并计入 devmind.task.dead 指标。
     */
    @Scheduled(
            fixedDelayString = "${devmind.task-scan-interval-ms:60000}",
            initialDelayString = "${devmind.task-scan-initial-delay-ms:60000}"
    )
    public void scanDeadTasks() {
        for (Long taskId : taskQueue.drainDead()) {
            taskRepository.markDead(taskId, "消息级重试超限，进入死信队列");
            meterRegistry.counter("devmind.task.dead").increment();
            log.error("任务进入死信队列并标记 DEAD，taskId={}", taskId);
        }
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
