package com.devmind.evaluation;

import com.devmind.agent.AgentService;
import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.ToolTraceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 行为级评测（真实模型版，P0-3）：
 * 用真实模型链路（ChatRouter → 智谱 glm-4.7-flash）驱动 AgentService 的 ReAct 循环，
 * 量化真实环境下的「工具选择准确率」与「任务完成率」。
 * <p>
 * 与 G5（AgentBehaviorEvalTest，mock 脚本可控，证明框架能跑）互补：
 * 本 runner 回答"真实模型下 Agent 会不会真的选对工具、完成任务"。
 * <p>
 * 运行（容器内，需要真实模型 key + DB）：
 * {@code docker compose run --rm --no-deps app java -jar /app/app.jar --agent-eval}
 */
@Component
public class AgentBehaviorEvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentBehaviorEvalRunner.class);

    private final AgentService agentService;

    public AgentBehaviorEvalRunner(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 评测用例：任务描述 + 可接受的工具集合（真实模型选到其中任意一个即算工具选择正确） */
    private record AgentEvalCase(String name, String question, List<String> acceptableTools) {
    }

    private List<AgentEvalCase> cases() {
        return List.of(
                new AgentEvalCase("知识检索", "知识库里什么是 RAG？", List.of("kb_search")),
                new AgentEvalCase("SQL 诊断", "诊断这条 SQL 慢在哪：SELECT * FROM orders WHERE user_id = 1 ORDER BY created_at",
                        List.of("sql_diagnose")),
                new AgentEvalCase("文档检索", "知识库里有关于线程池的文档吗？帮我找一下", List.of("doc_search", "kb_search")),
                new AgentEvalCase("直接回答", "你好", List.of())
        );
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("agent-eval")) {
            return;
        }
        log.info("=== Agent 行为级评测开始（真实模型）===");
        List<AgentEvalCase> cases = cases();
        List<String> report = new ArrayList<>();
        int toolHits = 0;
        int tasksDone = 0;

        for (AgentEvalCase c : cases) {
            try {
                AgentChatResponse resp = agentService.chat(new AgentChatRequest(0L, c.question(), null), 1L);
                List<String> actual = resp.toolTrace().stream()
                        .filter(ToolTraceItem::ok)
                        .map(ToolTraceItem::tool)
                        .toList();
                boolean toolOk = c.acceptableTools().isEmpty()
                        ? actual.isEmpty()
                        : !actual.isEmpty() && c.acceptableTools().containsAll(actual);
                boolean taskOk = resp.answer() != null && !resp.answer().isBlank();
                if (toolOk) {
                    toolHits++;
                }
                if (taskOk) {
                    tasksDone++;
                }
                report.add(String.format("  [%s] 期望工具=%s 实际=%s 工具选择%s 任务完成%s 回答=%s",
                        c.name(), c.acceptableTools(), actual, toolOk ? "✓" : "✗",
                        taskOk ? "✓" : "✗", truncate(resp.answer(), 60)));
            } catch (Exception ex) {
                report.add(String.format("  [%s] 执行异常: %s", c.name(),
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }

        String summary = String.format("""
                ===== Agent 行为级评测报告（真实模型，P0-3）=====
                %s
                指标：
                  - 工具选择准确率: %d/%d = %.1f%%
                  - 任务完成率: %d/%d = %.1f%%
                ================================================
                """,
                String.join("\n", report),
                toolHits, cases.size(), cases.size() == 0 ? 0 : toolHits * 100.0 / cases.size(),
                tasksDone, cases.size(), cases.size() == 0 ? 0 : tasksDone * 100.0 / cases.size());
        System.out.println(summary);
        log.info("=== Agent 行为级评测结束 ===");
        // 离线 CLI：评估完成后显式退出 JVM
        System.exit(0);
    }

    private String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
