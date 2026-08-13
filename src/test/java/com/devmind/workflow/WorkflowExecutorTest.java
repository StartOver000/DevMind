package com.devmind.workflow;

import com.devmind.agent.ToolRegistry;
import com.devmind.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutorTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private WorkflowRunRepository runRepository;

    private WorkflowExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executor = new WorkflowExecutor(toolRegistry, runRepository, objectMapper,
                new WorkflowConditionEvaluator());
    }

    private Workflow workflow(String stepsJson) {
        return new Workflow(1L, 1L, "测试流程", null, stepsJson, "manual", null, "private", "ENABLED", 1L, null);
    }

    private WorkflowRun run(Long id, String status) {
        return new WorkflowRun(id, 1L, 1L, "manual", status, 0.0, null, null, null);
    }

    @Test
    void executesStepsInOrderAndPassesVarsToNextStep() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("customer_query"), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenReturn("{\"clients\":[1,2]}");
        when(toolRegistry.execute(eq("ai_generate"), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenReturn("日报：2 个新客户");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"customer_query","params":{"days":1},"output_var":"clients"},
                 {"tool":"ai_generate","params":{"prompt":"基于 {{clients}} 生成日报"}}]
                """;
        WorkflowRun result = executor.execute(workflow(stepsJson), 1L, "manual");

        assertThat(result.status()).isEqualTo("SUCCESS");
        // 第 2 步收到第 1 步输出注入的变量（JSON 值已转义为合法 JSON）
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(toolRegistry).execute(eq("ai_generate"), inputCaptor.capture(), eq(1L), eq("workflow"), eq(100L));
        String input = inputCaptor.getValue();
        assertThat(input).contains("clients");
        try {
            objectMapper.readTree(input);
        } catch (Exception ex) {
            throw new AssertionError("注入后参数不是合法 JSON: " + input, ex);
        }
        // 步骤记录：2 条 SUCCESS（costMs 不精确断言，执行耗时不定）
        verify(runRepository).insertStep(eq(100L), eq(0), eq("customer_query"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
        verify(runRepository).insertStep(eq(100L), eq(1), eq("ai_generate"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
    }

    @Test
    void stopsOnStepFailureAndMarksRunFailed() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("customer_query"), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenThrow(new RuntimeException("接口 500"));
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "FAILED"));

        String stepsJson = """
                [{"tool":"customer_query","params":{},"output_var":"c"},
                 {"tool":"ai_generate","params":{"prompt":"x"}}]
                """;
        WorkflowRun result = executor.execute(workflow(stepsJson), 1L, "manual");

        assertThat(result.status()).isEqualTo("FAILED");
        // 失败步骤记录 FAILED，后续步骤不执行
        verify(runRepository).insertStep(eq(100L), eq(0), eq("customer_query"), anyString(), eq(null), eq("FAILED"), anyLong(), anyString());
        verify(toolRegistry, never()).execute(eq("ai_generate"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(runRepository).finishRun(eq(100L), eq("FAILED"), anyString());
    }

    @Test
    void rejectsWhenWorkflowAlreadyRunning() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> executor.execute(
                workflow("[{\"tool\":\"a\",\"params\":{}}]"), 1L, "manual"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正在执行");
        verify(runRepository, never()).insertRun(anyLong(), anyLong(), anyString());
    }

    @Test
    void fillsJsonValueIntoNextStepParamsAsEscapedText() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        // 第 1 步输出是 JSON 文本
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenReturn("{\"version\":\"3.13.2\"}");
        when(toolRegistry.execute(eq("ai_generate"), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenReturn("ok");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"a","params":{},"output_var":"info"},
                 {"tool":"ai_generate","params":{"prompt":"总结：{{info}}"}}]
                """;
        executor.execute(workflow(stepsJson), 1L, "manual");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(toolRegistry).execute(eq("ai_generate"), captor.capture(), eq(1L), eq("workflow"), eq(100L));
        String input = captor.getValue();
        // 注入后的参数必须是合法 JSON（JSON 值已被转义）
        assertThat(input).startsWith("{\"prompt\":\"总结：");
        assertThat(input).contains("\\\"version\\\":\\\"3.13.2\\\"");
        try {
            objectMapper.readTree(input);
        } catch (Exception ex) {
            throw new AssertionError("注入后参数不是合法 JSON: " + input, ex);
        }
    }

    @Test
    void rejectsEmptySteps() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> executor.execute(workflow("[]"), 1L, "manual"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("步骤为空");
    }

    @Test
    void parallelStepsAllExecuteAndRecordWithDistinctIndexes() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("A");
        when(toolRegistry.execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("B");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"parallel":[
                   {"tool":"a","params":{},"output_var":"va"},
                   {"tool":"b","params":{},"output_var":"vb"}
                 ]}]
                """;
        WorkflowRun result = executor.execute(workflow(stepsJson), 1L, "manual");

        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(toolRegistry).execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(toolRegistry).execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(runRepository).insertStep(eq(100L), eq(0), eq("a"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
        verify(runRepository).insertStep(eq(100L), eq(1), eq("b"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
    }

    @Test
    void parallelGroupFailureStillExecutesAllAndMarksRunFailed() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenThrow(new RuntimeException("a 挂了"));
        when(toolRegistry.execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("B");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "FAILED"));

        String stepsJson = """
                [{"parallel":[
                   {"tool":"a","params":{},"output_var":"va"},
                   {"tool":"b","params":{},"output_var":"vb"}
                 ]}]
                """;
        WorkflowRun result = executor.execute(workflow(stepsJson), 1L, "manual");

        assertThat(result.status()).isEqualTo("FAILED");
        // 并行组不因单个失败中断：两个都执行并记录
        verify(toolRegistry).execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(toolRegistry).execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(runRepository).insertStep(eq(100L), eq(0), eq("a"), anyString(), eq(null), eq("FAILED"), anyLong(), anyString());
        verify(runRepository).insertStep(eq(100L), eq(1), eq("b"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
    }

    @Test
    void mixedSequentialAndParallelStepsKeepIndexOrderAndInjectVars() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("c"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("C");
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("A");
        when(toolRegistry.execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("B");
        when(toolRegistry.execute(eq("d"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("D");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"c","params":{},"output_var":"vc"},
                 {"parallel":[
                    {"tool":"a","params":{},"output_var":"va"},
                    {"tool":"b","params":{},"output_var":"vb"}
                 ]},
                 {"tool":"d","params":{"summary":"{{va}} {{vb}}"}}]
                """;
        executor.execute(workflow(stepsJson), 1L, "manual");

        // index 连续：c=0, a=1, b=2, d=3
        verify(runRepository).insertStep(eq(100L), eq(0), eq("c"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
        verify(runRepository).insertStep(eq(100L), eq(1), eq("a"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
        verify(runRepository).insertStep(eq(100L), eq(2), eq("b"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
        verify(runRepository).insertStep(eq(100L), eq(3), eq("d"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
        // 并行步输出注入到后续步骤参数
        ArgumentCaptor<String> dInput = ArgumentCaptor.forClass(String.class);
        verify(toolRegistry).execute(eq("d"), dInput.capture(), eq(1L), eq("workflow"), eq(100L));
        assertThat(dInput.getValue()).contains("A").contains("B");
    }

    @Test
    void ifBranchExecutesThenWhenConditionTrue() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("100");
        when(toolRegistry.execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("B");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"a","params":{},"output_var":"va"},
                 {"if":"{{va}} > 50","then":[{"tool":"b","params":{},"output_var":"vb"}],
                  "else":[{"tool":"c","params":{},"output_var":"vc"}]}]
                """;
        executor.execute(workflow(stepsJson), 1L, "manual");

        // then 分支执行（b），else 分支不执行（c）
        verify(toolRegistry).execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(toolRegistry, never()).execute(eq("c"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(runRepository).insertStep(eq(100L), eq(1), eq("b"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
    }

    @Test
    void ifBranchExecutesElseWhenConditionFalse() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("10");
        when(toolRegistry.execute(eq("c"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("C");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"a","params":{},"output_var":"va"},
                 {"if":"{{va}} > 50","then":[{"tool":"b","params":{},"output_var":"vb"}],
                  "else":[{"tool":"c","params":{},"output_var":"vc"}]}]
                """;
        executor.execute(workflow(stepsJson), 1L, "manual");

        // else 分支执行（c），then 分支不执行（b）
        verify(toolRegistry, never()).execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(toolRegistry).execute(eq("c"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(runRepository).insertStep(eq(100L), eq(1), eq("c"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
    }

    @Test
    void ifBranchWithoutElseSkipsWhenFalse() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("10");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"a","params":{},"output_var":"va"},
                 {"if":"{{va}} > 50","then":[{"tool":"b","params":{},"output_var":"vb"}]}]
                """;
        executor.execute(workflow(stepsJson), 1L, "manual");

        // 条件 false 且无 else：跳过，只有 a 执行
        verify(toolRegistry, never()).execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L));
        verify(runRepository).insertStep(eq(100L), eq(0), eq("a"), anyString(), anyString(), eq("SUCCESS"), anyLong(), eq(null));
    }

    @Test
    void stringConditionComparesValues() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("success");
        when(toolRegistry.execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("B");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"a","params":{},"output_var":"status"},
                 {"if":"{{status}} == 'success'","then":[{"tool":"b","params":{},"output_var":"vb"}]}]
                """;
        executor.execute(workflow(stepsJson), 1L, "manual");

        verify(toolRegistry).execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L));
    }

    @Test
    void containsConditionMatches() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenReturn("{\"data\":{\"version\":\"3.13.2\"}}");
        when(toolRegistry.execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L))).thenReturn("B");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"a","params":{},"output_var":"info"},
                 {"if":"{{info}} contains 'version'","then":[{"tool":"b","params":{},"output_var":"vb"}]}]
                """;
        executor.execute(workflow(stepsJson), 1L, "manual");

        verify(toolRegistry).execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L));
    }

    @Test
    void notContainsConditionSkips() {
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(toolRegistry.execute(eq("a"), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenReturn("{\"data\":{\"version\":\"3.13.2\"}}");
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "SUCCESS"));

        String stepsJson = """
                [{"tool":"a","params":{},"output_var":"info"},
                 {"if":"{{info}} not contains 'error'","then":[{"tool":"b","params":{},"output_var":"vb"}]}]
                """;
        executor.execute(workflow(stepsJson), 1L, "manual");

        // info 不含 error → not contains 为 true → b 执行
        verify(toolRegistry).execute(eq("b"), anyString(), eq(1L), eq("workflow"), eq(100L));
    }

    @Test
    void hangingToolTimesOutAndFailsRunInsteadOfBlockingForever() throws Exception {
        // 工具调用永久挂起（模拟底层模型/接口卡死）：30s 超时应标记 FAILED 并 finishRun，
        // 而不是像以前那样 run 永久 RUNNING 阻塞调度。
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        // 阻塞 60s（超过 30s 超时阈值），模拟挂起
        org.mockito.Mockito.doAnswer(inv -> {
            Thread.sleep(60_000);
            return "never";
        }).when(toolRegistry).execute(eq("hang_tool"), anyString(), eq(1L), eq("workflow"), eq(100L));
        when(runRepository.findRun(1L, 100L)).thenReturn(run(100L, "FAILED"));

        String stepsJson = "[{\"tool\":\"hang_tool\",\"params\":{}}]";
        long start = System.currentTimeMillis();
        executor.execute(workflow(stepsJson), 1L, "manual");
        long elapsed = System.currentTimeMillis() - start;

        // 在 ~30s 超时后返回（而非 60s），且 run 被标记 FAILED
        assertThat(elapsed).isLessThan(50_000);
        verify(runRepository).insertStep(eq(100L), eq(0), eq("hang_tool"), anyString(),
                eq(null), eq("FAILED"), anyLong(), org.mockito.ArgumentMatchers.contains("超时"));
        verify(runRepository).finishRun(eq(100L), eq("FAILED"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void queuesConcurrentTriggersInsteadOfRejecting() throws Exception {
        // 同一工作流并发触发：第二个排队等待第一个完成，而不是被拒绝（修复 webhook 风暴失败）
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L, 101L);
        // 工具执行慢一点（100ms），制造第一个 run 尚未结束、第二个已到达的排队窗口
        when(toolRegistry.execute(anyString(), anyString(), eq(1L), eq("workflow"), anyLong()))
                .thenAnswer(inv -> {
                    Thread.sleep(100);
                    return "{\"ok\":true}";
                });
        when(runRepository.findRun(anyLong(), anyLong()))
                .thenAnswer(inv -> run(inv.getArgument(1), "SUCCESS"));

        Workflow wf = workflow("[{\"tool\":\"slow\",\"params\":{},\"output_var\":\"v\"}]");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WorkflowRun>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return executor.execute(wf, 1L, "manual");
            }));
        }
        start.countDown();
        for (Future<WorkflowRun> future : futures) {
            assertThat(future.get(10, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
        }
        pool.shutdownNow();

        // 两次触发都执行了（排队串行），而非第二次抛"正在执行中"
        verify(runRepository, times(2)).insertRun(1L, 1L, "manual");
        // 两个 run 都各执行了工具
        verify(toolRegistry, times(2)).execute(anyString(), anyString(), eq(1L), eq("workflow"), anyLong());
    }

    @Test
    void differentWorkflowsDoNotBlockEachOther() throws Exception {
        // 不同工作流互不影响：A 慢执行时，B 无需排队即可执行（gate 按 workflowId 隔离）
        when(runRepository.hasRunning(1L, 1L)).thenReturn(false);
        when(runRepository.hasRunning(1L, 2L)).thenReturn(false);
        when(runRepository.insertRun(1L, 1L, "manual")).thenReturn(100L);
        when(runRepository.insertRun(2L, 1L, "manual")).thenReturn(200L);
        when(toolRegistry.execute(anyString(), anyString(), eq(1L), eq("workflow"), eq(100L)))
                .thenAnswer(inv -> {
                    Thread.sleep(200);
                    return "{\"ok\":true}";
                });
        when(toolRegistry.execute(anyString(), anyString(), eq(1L), eq("workflow"), eq(200L)))
                .thenReturn("{\"ok\":true}");
        when(runRepository.findRun(anyLong(), anyLong()))
                .thenAnswer(inv -> run(inv.getArgument(1), "SUCCESS"));

        Workflow slow = workflow("[{\"tool\":\"slow\",\"params\":{},\"output_var\":\"v\"}]");
        // workflow id=2 的工作流（不同流程）
        Workflow fast = new Workflow(2L, 1L, "另一个流程", null,
                "[{\"tool\":\"fast\",\"params\":{},\"output_var\":\"v\"}]",
                "manual", null, "private", "ENABLED", 1L, null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch slowDone = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        pool.submit(() -> {
            start.await();
            executor.execute(slow, 1L, "manual");
            slowDone.countDown();
            return null;
        });
        pool.submit(() -> {
            start.await();
            executor.execute(fast, 1L, "manual");
            fastDone.countDown();
            return null;
        });
        start.countDown();
        // fast（id=2）不被 slow（id=1，200ms）阻塞：150ms 内应已完成；slow 随后完成
        assertThat(fastDone.await(150, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(slowDone.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        verify(runRepository).insertRun(1L, 1L, "manual");
        verify(runRepository).insertRun(2L, 1L, "manual");
    }
}
