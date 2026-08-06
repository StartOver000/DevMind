package com.devmind.evaluation;

import com.devmind.agent.AgentService;
import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.evaluation.dto.AgentEvaluationResponse;
import com.devmind.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentEvaluationServiceTest {

    @Mock
    private AgentService agentService;

    @Mock
    private UserService userService;

    private AgentEvaluationService service() {
        return new AgentEvaluationService(agentService, userService,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void countsPassedWhenExpectedToolsCalled() {
        when(agentService.chat(any(), any())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0, AgentChatRequest.class).question();
            if (question.contains("SQL 慢")) {
                return new AgentChatResponse(1L, "诊断结果", List.of(),
                        List.of(new ToolTraceItem("sql_diagnose", "{}", true, 10)));
            }
            if (question.contains("深分页")) {
                return new AgentChatResponse(2L, "检索结果", List.of(),
                        List.of(new ToolTraceItem("kb_search", "{}", true, 5)));
            }
            if (question.contains("分析这个 SQL")) {
                // 只调了 sql_diagnose，缺少期望的 kb_search → 不通过
                return new AgentChatResponse(3L, "分析结果", List.of(),
                        List.of(new ToolTraceItem("sql_diagnose", "{}", true, 10)));
            }
            if (question.contains("有哪些内容")) {
                return new AgentChatResponse(4L, "库列表", List.of(),
                        List.of(new ToolTraceItem("kb_info", "{}", true, 5)));
            }
            if (question.contains("先看看")) {
                return new AgentChatResponse(5L, "结果", List.of(),
                        List.of(new ToolTraceItem("kb_info", "{}", true, 5),
                                new ToolTraceItem("kb_search", "{}", true, 5)));
            }
            return new AgentChatResponse(6L, "你好，我是研发助手", List.of(), List.of());
        });

        AgentEvaluationResponse response = service().evaluate(1L);

        assertThat(response.total()).isEqualTo(9);
        // 通过：SQL(1)、深分页(2)、有哪些内容(4)、先看看(5)、自我介绍(9) = 5 条（其余场景 mock 未返回对应工具，不通过）
        assertThat(response.passed()).isEqualTo(5);
        assertThat(response.passRate()).isEqualTo(5.0 / 9.0);
        // 第 3 条（分析 SQL 并找方案）期望两个工具但只调一个 → 不通过
        assertThat(response.items().get(2).toolMatch()).isFalse();
    }

    @Test
    void toolFailureMarksCaseFailed() {
        when(agentService.chat(any(), any())).thenReturn(new AgentChatResponse(
                1L, "", List.of(),
                List.of(new ToolTraceItem("sql_diagnose", "{}", false, 10))
        ));
        AgentEvaluationResponse response = service().evaluate(1L);
        assertThat(response.passed()).isEqualTo(0);
        assertThat(response.items().get(0).toolsOk()).isFalse();
    }
}
