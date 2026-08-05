package com.devmind.evaluation;

import com.devmind.agent.AgentService;
import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.evaluation.dto.AgentEvalItem;
import com.devmind.evaluation.dto.AgentEvaluationResponse;
import com.devmind.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 评估：跑一组研发场景，量化"多工具编排成功率"。
 * 判定标准：期望工具都被调用（toolMatch）且全部执行成功（toolsOk）且回答非空。
 */
@Service
public class AgentEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AgentEvaluationService.class);

    private final AgentService agentService;
    private final UserService userService;

    public AgentEvaluationService(AgentService agentService, UserService userService) {
        this.agentService = agentService;
        this.userService = userService;
    }

    public AgentEvaluationResponse evaluate(Long userId) {
        userService.requireUser(userId);
        List<AgentEvalItem> items = new ArrayList<>();
        int passed = 0;
        for (EvalCase evalCase : CASES) {
            try {
                AgentChatResponse response = agentService.chat(
                        new AgentChatRequest(0L, evalCase.question(), null),
                        userId
                );
                Set<String> called = new LinkedHashSet<>();
                boolean toolsOk = true;
                for (ToolTraceItem trace : response.toolTrace()) {
                    called.add(trace.tool());
                    if (!trace.ok()) {
                        toolsOk = false;
                    }
                }
                boolean toolMatch = evalCase.expectedTools().stream().allMatch(called::contains);
                boolean pass = toolMatch && toolsOk && response.answer() != null && !response.answer().isBlank();
                if (pass) {
                    passed++;
                }
                items.add(new AgentEvalItem(
                        evalCase.question(),
                        evalCase.expectedTools(),
                        List.copyOf(called),
                        toolMatch,
                        toolsOk,
                        response.answer() == null ? 0 : response.answer().length()
                ));
            } catch (Exception ex) {
                log.warn("agent 评估用例失败: {} -> {}", evalCase.question(), ex.getMessage());
                items.add(new AgentEvalItem(evalCase.question(), evalCase.expectedTools(), List.of(), false, false, 0));
            }
        }
        int total = CASES.size();
        double passRate = total == 0 ? 0 : (double) passed / total;
        return new AgentEvaluationResponse(total, passed, passRate, items);
    }

    private record EvalCase(String question, List<String> expectedTools) {
    }

    private static final List<EvalCase> CASES = List.of(
            new EvalCase("SELECT * FROM orders ORDER BY created_time LIMIT 100000, 20 这个 SQL 慢在哪？", List.of("sql_diagnose")),
            new EvalCase("深分页为什么慢？检索一下知识库里的资料", List.of("kb_search")),
            new EvalCase("分析这个 SQL 的性能问题，并从知识库找优化方案", List.of("sql_diagnose", "kb_search")),
            new EvalCase("知识库里有哪些内容？", List.of("kb_info")),
            new EvalCase("知识库里有索引相关的文档吗？", List.of("doc_list")),
            new EvalCase("我今天的模型用量和费用是多少？", List.of("usage_query")),
            new EvalCase("先看看知识库有什么，再帮我查一下索引优化资料", List.of("kb_info", "kb_search")),
            new EvalCase("你好，介绍一下你自己", List.of())
    );
}
