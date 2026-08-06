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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
        executor = new WorkflowExecutor(toolRegistry, runRepository, objectMapper);
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
}
