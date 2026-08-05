package com.devmind.document;

import com.devmind.ai.AiModelGateway;
import com.devmind.config.DevMindProperties;
import com.devmind.document.chunker.TextChunk;
import com.devmind.document.chunker.TextChunker;
import com.devmind.document.chunker.TextChunkerFactory;
import com.devmind.document.parser.DocumentParserRegistry;
import com.devmind.retrieval.ChunkRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentTaskServiceTest {

    @Mock
    private DocumentTaskRepository taskRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentParserRegistry parserRegistry;

    @Mock
    private TextChunker textChunker;

    @Mock
    private TextChunkerFactory chunkerFactory;

    @Mock
    private ChunkRepository chunkRepository;

    @Mock
    private AiModelGateway modelGateway;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private MeterRegistry meterRegistry;

    private final DevMindProperties properties = new DevMindProperties(
            "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
            4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
            0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
    );

    @BeforeEach
    void setUpMeterRegistry() {
        when(meterRegistry.counter(anyString())).thenReturn(mock(Counter.class));
        when(chunkerFactory.get()).thenReturn(textChunker);
    }

    @Test
    void scanDeadTasksMarksDeadAndCounts() {
        TaskQueue queue = mock(TaskQueue.class);
        DocumentTaskService service = new DocumentTaskService(
                taskRepository,
                documentRepository,
                parserRegistry,
                chunkerFactory,
                chunkRepository,
                modelGateway,
                queue,
                taskScheduler,
                properties,
                meterRegistry
        );
        when(queue.drainDead()).thenReturn(List.of(11L, 22L));

        service.scanDeadTasks();

        verify(taskRepository).markDead(11L, "消息级重试超限，进入死信队列");
        verify(taskRepository).markDead(22L, "消息级重试超限，进入死信队列");
        verify(meterRegistry, times(2)).counter("devmind.task.dead");
    }

    @Test
    void completesTaskSuccessfully(@TempDir Path tempDir) throws Exception {
        DocumentTaskService service = new DocumentTaskService(
                taskRepository,
                documentRepository,
                parserRegistry,
                chunkerFactory,
                chunkRepository,
                modelGateway,
                new InMemoryTaskQueue(taskExecutor),
                taskScheduler,
                properties,
                meterRegistry
        );
        Path file = tempDir.resolve("a.md");
        Files.writeString(file, "hello");
        DocumentTask task = new DocumentTask(1L, 10L, "PENDING", 0, 3, null, null, null);
        Document document = new Document(
                10L, 1L, "a.md", "markdown", 5L, file.toString(), "hash", "UPLOADED", null, null, null, null, Map.of()
        );
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(taskRepository.claimForProcessing(1L)).thenReturn(true);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(parserRegistry.parse(anyString(), anyString(), any(byte[].class))).thenReturn("hello");
        when(textChunker.chunk("hello")).thenReturn(List.of(new TextChunk(0, "hello", "title")));
        when(chunkRepository.findHashSetByDocument(any())).thenReturn(new java.util.HashSet<>());
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(1.0, 0.0)));

        service.processTask(1L);

        verify(documentRepository).updateStatus(10L, "COMPLETED", null);
        verify(taskRepository).markSucceeded(1L);
        verify(chunkRepository).updateChunksIncremental(eq(10L), any(), any(), any(), any());
    }

    @Test
    void reusesUnchangedChunksIncrementally(@TempDir Path tempDir) throws Exception {
        DocumentTaskService service = new DocumentTaskService(
                taskRepository,
                documentRepository,
                parserRegistry,
                chunkerFactory,
                chunkRepository,
                modelGateway,
                new InMemoryTaskQueue(taskExecutor),
                taskScheduler,
                properties,
                meterRegistry
        );
        Path file = tempDir.resolve("a.md");
        Files.writeString(file, "hello");
        DocumentTask task = new DocumentTask(1L, 10L, "PENDING", 0, 3, null, null, null);
        Document document = new Document(
                10L, 1L, "a.md", "markdown", 5L, file.toString(), "hash", "UPLOADED", null, null, null, null, Map.of()
        );
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(taskRepository.claimForProcessing(1L)).thenReturn(true);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(parserRegistry.parse(anyString(), anyString(), any(byte[].class))).thenReturn("hello");
        when(textChunker.chunk("hello")).thenReturn(List.of(new TextChunk(0, "hello", "title")));
        // 存量哈希已包含该片段内容 → 全部复用，不调 embedding、不插入新片段
        String existingHash = com.devmind.common.HashUtils.sha256("hello");
        when(chunkRepository.findHashSetByDocument(any())).thenReturn(new java.util.HashSet<>(java.util.Set.of(existingHash)));

        service.processTask(1L);

        verify(modelGateway, never()).embed(anyList());
        org.mockito.ArgumentCaptor<List> changedCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).updateChunksIncremental(eq(10L), changedCaptor.capture(), any(), any(), any());
        assertThat(changedCaptor.getValue()).isEmpty();
        verify(documentRepository).updateStatus(10L, "COMPLETED", null);
    }

    @Test
    void retriesWhenProcessingFails(@TempDir Path tempDir) throws Exception {
        DocumentTaskService service = new DocumentTaskService(
                taskRepository,
                documentRepository,
                parserRegistry,
                chunkerFactory,
                chunkRepository,
                modelGateway,
                new InMemoryTaskQueue(taskExecutor),
                taskScheduler,
                properties,
                meterRegistry
        );
        Path file = tempDir.resolve("a.md");
        Files.writeString(file, "hello");
        DocumentTask task = new DocumentTask(1L, 10L, "PENDING", 0, 3, null, null, null);
        Document document = new Document(
                10L, 1L, "a.md", "markdown", 5L, file.toString(), "hash", "UPLOADED", null, null, null, null, Map.of()
        );
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(taskRepository.claimForProcessing(1L)).thenReturn(true);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(parserRegistry.parse(anyString(), anyString(), any(byte[].class)))
                .thenThrow(new RuntimeException("parse failed"));

        // 重试路径：向上抛异常，由 MQ 消息级重投（不再 scheduleRetry）
        assertThatThrownBy(() -> service.processTask(1L)).isInstanceOf(RuntimeException.class);

        verify(taskRepository).markFailedForRetry(eq(1L), anyString());
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        verify(documentRepository, never()).updateStatus(eq(10L), eq("FAILED"), anyString());
    }

    @Test
    void failsPermanentlyAfterMaxRetries(@TempDir Path tempDir) throws Exception {
        DocumentTaskService service = new DocumentTaskService(
                taskRepository,
                documentRepository,
                parserRegistry,
                chunkerFactory,
                chunkRepository,
                modelGateway,
                new InMemoryTaskQueue(taskExecutor),
                taskScheduler,
                properties,
                meterRegistry
        );
        Path file = tempDir.resolve("a.md");
        Files.writeString(file, "hello");
        DocumentTask task = new DocumentTask(1L, 10L, "PENDING", 2, 3, null, null, null);
        Document document = new Document(
                10L, 1L, "a.md", "markdown", 5L, file.toString(), "hash", "UPLOADED", null, null, null, null, Map.of()
        );
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(taskRepository.claimForProcessing(1L)).thenReturn(true);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(parserRegistry.parse(anyString(), anyString(), any(byte[].class)))
                .thenThrow(new RuntimeException("parse failed"));

        service.processTask(1L);

        verify(taskRepository).markFailedPermanent(eq(1L), anyString());
        verify(documentRepository).updateStatus(10L, "FAILED", "parse failed");
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }
}
