package com.devmind.evaluation;

import com.devmind.agent.AgentService;
import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.ToolTraceItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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
    /** 回退告警阈值：低于基线 5% 即告警（与检索护栏一致） */
    private static final double REGRESSION_THRESHOLD = 0.95;
    /** 基线文件：data/eval/agent-baseline.json */
    private static final Path BASELINE_PATH = Path.of("data/eval/agent-baseline.json");

    private final AgentService agentService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentBehaviorEvalRunner(AgentService agentService, MeterRegistry meterRegistry) {
        this.agentService = agentService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 评测用例：任务描述 + 可接受的工具集合（选到任意一个即算对）+ 必调工具集合（复合任务全部调用才算对）。
     * requiredTools 非空时判定更严：实际成功调用必须包含全部必调工具（验证复合句拆句 + 连续多工具选择）。
     */
    private record AgentEvalCase(String name, String question, List<String> acceptableTools, List<String> requiredTools) {
        AgentEvalCase(String name, String question, List<String> acceptableTools) {
            this(name, question, acceptableTools, List.of());
        }
    }

    private List<AgentEvalCase> cases() {
        return List.of(
                new AgentEvalCase("知识检索", "知识库里什么是 RAG？", List.of("kb_search")),
                new AgentEvalCase("SQL 诊断", "诊断这条 SQL 慢在哪：SELECT * FROM orders WHERE user_id = 1 ORDER BY created_at",
                        List.of("sql_diagnose")),
                new AgentEvalCase("文档检索", "知识库里有关于线程池的文档吗？帮我找一下", List.of("doc_search", "kb_search")),
                new AgentEvalCase("直接回答", "你好", List.of()),
                // 复合任务（拆句 + 连续多工具选择）：两个子意图都必须被调用（内置工具，不受接口 401 影响）
                new AgentEvalCase("复合任务",
                        "先诊断这条 SQL 慢在哪：SELECT * FROM orders WHERE user_id = 1 ORDER BY created_at，然后再查一下知识库里什么是 RAG",
                        List.of("sql_diagnose", "kb_search"),
                        List.of("sql_diagnose", "kb_search")),
                // 接口工具选择（P1 接口语义化）：问题需语义命中注入的接口候选，再选对工具
                new AgentEvalCase("Stripe 余额", "查一下 Stripe 账户的当前余额是多少", List.of("GetBalance")),
                new AgentEvalCase("Stripe 交易", "列出 Stripe 的余额交易记录", List.of("GetBalanceTransactions")),
                // PostCharges 已被 Stripe 标记弃用（no longer recommended），现行推荐 PaymentIntent，两者皆可算对；
                // 必须带金额/币种（模型缺必填参数时澄清是合理行为，不算工具选择错误）
                new AgentEvalCase("Stripe 支付", "帮我在 Stripe 上创建一笔 100 美元的支付", List.of("PostCharges", "PostPaymentIntents"))
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
                // 工具选择正确：复合任务（必调集合非空）须全部调用；否则选到任意期望工具即可（额外探索工具不判错）
                boolean toolOk;
                if (!c.requiredTools().isEmpty()) {
                    toolOk = !actual.isEmpty() && actual.containsAll(c.requiredTools());
                } else if (c.acceptableTools().isEmpty()) {
                    toolOk = actual.isEmpty();
                } else {
                    toolOk = !actual.isEmpty() && actual.stream().anyMatch(c.acceptableTools()::contains);
                }
                boolean taskOk = resp.answer() != null && !resp.answer().isBlank();
                if (toolOk) {
                    toolHits++;
                }
                if (taskOk) {
                    tasksDone++;
                }
                report.add(String.format("  [%s] 期望工具=%s 实际=%s 工具选择%s 任务完成%s 回答=%s",
                        c.name(), c.requiredTools().isEmpty() ? c.acceptableTools() : "必调" + c.requiredTools(),
                        actual, toolOk ? "✓" : "✗",
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
        // 护栏：与基线对比（首次用 --update-baseline 建立基线），工具选择准确率回退超 5% 告警
        if (args.containsOption("update-baseline")) {
            writeBaseline(toolHits, tasksDone, cases.size());
        } else {
            checkBaseline(toolHits, tasksDone, cases.size());
        }
        log.info("=== Agent 行为级评测结束 ===");
        // 离线 CLI：评估完成后显式退出 JVM
        System.exit(0);
    }

    /** Agent 质量护栏：与基线对比，工具选择准确率 / 任务完成率回退超 5% 记 devmind.agent.regression 并告警 */
    private void checkBaseline(int toolHits, int tasksDone, int caseCount) {
        try {
            if (!Files.exists(BASELINE_PATH)) {
                log.warn("[GUARD] NO_BASELINE 未发现基线 {}，首次运行请加 --update-baseline 建立基线", BASELINE_PATH);
                return;
            }
            JsonNode base = objectMapper.readTree(Files.readString(BASELINE_PATH));
            double baseToolAcc = base.path("toolAccuracy").asDouble(0);
            double baseTaskRate = base.path("taskRate").asDouble(0);
            double toolAcc = caseCount == 0 ? 0 : toolHits * 100.0 / caseCount / 100.0;
            double taskRate = caseCount == 0 ? 0 : tasksDone * 100.0 / caseCount / 100.0;
            boolean regressed = (baseToolAcc > 0 && toolAcc < baseToolAcc * REGRESSION_THRESHOLD)
                    || (baseTaskRate > 0 && taskRate < baseTaskRate * REGRESSION_THRESHOLD);
            if (regressed) {
                meterRegistry.counter("devmind.agent.regression").increment();
                log.error("[GUARD] FAIL Agent 行为回退：工具选择准确率={}%%（基线 {}）任务完成率={}%%（基线 {}），请检查 Agent 配置/工具描述/模型链路",
                        roundPct(toolAcc), roundPct(baseToolAcc), roundPct(taskRate), roundPct(baseTaskRate));
            } else {
                log.info("[GUARD] PASS Agent 行为护栏通过：工具选择准确率={}%%（基线 {}），任务完成率={}%%（基线 {}）",
                        roundPct(toolAcc), roundPct(baseToolAcc), roundPct(taskRate), roundPct(baseTaskRate));
            }
        } catch (Exception ex) {
            log.warn("基线对比失败: {}", ex.getMessage());
        }
    }

    /** 把当前结果写为基线（--update-baseline） */
    private void writeBaseline(int toolHits, int tasksDone, int caseCount) {
        try {
            Files.createDirectories(BASELINE_PATH.getParent());
            double toolAcc = caseCount == 0 ? 0 : toolHits * 100.0 / caseCount / 100.0;
            double taskRate = caseCount == 0 ? 0 : tasksDone * 100.0 / caseCount / 100.0;
            Files.writeString(BASELINE_PATH, """
                    {
                      "caseCount": %d,
                      "toolHits": %d,
                      "tasksDone": %d,
                      "toolAccuracy": %.4f,
                      "taskRate": %.4f,
                      "updatedAt": "%s"
                    }
                    """.formatted(
                    caseCount, toolHits, tasksDone, toolAcc, taskRate, OffsetDateTime.now()));
            log.info("Agent 质量基线已写入 {}（工具选择准确率={}%%）", BASELINE_PATH, roundPct(toolAcc));
        } catch (Exception ex) {
            log.warn("基线写盘失败: {}", ex.getMessage());
        }
    }

    private double roundPct(double ratio) {
        return Math.round(ratio * 1000) / 10.0;
    }

    private String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
