package com.devmind.evaluation;

import com.devmind.chat.ChatService;
import com.devmind.chat.dto.ChatRequest;
import com.devmind.chat.dto.ChatResponse;
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
 * 回答质量抽检 Runner（P0-3，质量护栏第三层）：
 * 对知识库的真实问题跑完整问答链路（检索 + 生成），统计质量信号。
 * <p>
 * 与另两层护栏互补：
 * - 检索护栏（RetrievalEvalRunner）：向量/关键词检索是否命中（MRR/Recall）
 * - Agent 护栏（AgentBehaviorEvalRunner）：真实模型下工具选择/任务完成
 * - 本层：端到端回答是否为空/降级、引用是否够（回答质量不因检索或模型变更而回退）
 * <p>
 * 运行（容器内，需要真实模型 key + DB）：
 * {@code docker compose run --rm --no-deps app java -jar /app/app.jar --answer-audit}
 * 首次建立基线：{@code ... --answer-audit --update-baseline}
 */
@Component
public class AnswerQualityEvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnswerQualityEvalRunner.class);
    /** 抽检知识库（JavaGuide 专题库） */
    private static final long EVAL_KB_ID = 19L;
    /** 基线文件：data/eval/answer-baseline.json */
    private static final Path BASELINE_PATH = Path.of("data/eval/answer-baseline.json");
    /** 回退告警阈值：平均引用数低于基线 5% 即告警（空回答/降级为硬性 FAIL） */
    private static final double REGRESSION_THRESHOLD = 0.95;
    /** 本地降级模式标记（模型降级时回答内出现） */
    private static final String DEGRADED_MARKER = "本地降级模式";

    private final ChatService chatService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnswerQualityEvalRunner(ChatService chatService, MeterRegistry meterRegistry) {
        this.chatService = chatService;
        this.meterRegistry = meterRegistry;
    }

    private List<String> questions() {
        return List.of(
                "GraphRAG 是什么？",
                "RAG 的工作原理是什么？",
                "什么是向量检索？",
                "重排序能提升检索精度吗？",
                "切块大小影响检索效果吗？",
                "LLM 网关是什么？",
                "Function Calling 是什么？",
                "什么是 MCP？",
                "深分页会导致什么问题？",
                "怎么降低 RAG 的检索成本？"
        );
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("answer-audit")) {
            return;
        }
        log.info("=== 回答质量抽检开始（知识库 {}，{} 题）===", EVAL_KB_ID, questions().size());
        List<String> report = new ArrayList<>();
        int empty = 0;
        int degraded = 0;
        int degradedNoRef = 0;
        int totalRefs = 0;
        int totalLen = 0;

        for (String q : questions()) {
            try {
                ChatResponse resp = chatService.chat(
                        EVAL_KB_ID, new ChatRequest(q, null, null, null), 1L);
                String ans = resp.answer() == null ? "" : resp.answer();
                int refs = resp.references() == null ? 0 : resp.references().size();
                boolean isEmpty = ans.isBlank();
                boolean isDegraded = ans.contains(DEGRADED_MARKER);
                if (isEmpty) {
                    empty++;
                }
                if (isDegraded) {
                    degraded++;
                    if (refs == 0) {
                        degradedNoRef++;
                    }
                }
                totalRefs += refs;
                totalLen += ans.length();
                report.add(String.format("  [%s] len=%d refs=%d %s",
                        truncate(q, 18), ans.length(), refs,
                        isEmpty ? "【空】" : isDegraded ? "【降级】" : ""));
            } catch (Exception ex) {
                report.add(String.format("  [%s] 执行异常: %s", truncate(q, 18),
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }

        int total = questions().size();
        double avgRefs = total == 0 ? 0 : totalRefs * 1.0 / total;
        String summary = String.format("""
                ===== 回答质量抽检报告（知识库 %d，%d 题）=====
                %s
                指标：
                  - 空回答: %d/%d
                  - 本地降级: %d/%d
                  - 降级且无引用: %d
                  - 平均引用数: %.1f
                  - 平均回答长度: %d 字符
                ================================================
                """,
                EVAL_KB_ID, total, String.join("\n", report),
                empty, total, degraded, total, degradedNoRef, avgRefs,
                total == 0 ? 0 : totalLen / total);
        System.out.println(summary);
        // 护栏：空回答/降级为硬性 FAIL，平均引用回退超 5% 为软告警（首次 --update-baseline 建立基线）
        if (args.containsOption("update-baseline")) {
            writeBaseline(total, empty, degraded, avgRefs);
        } else {
            checkBaseline(total, empty, degraded, avgRefs);
        }
        log.info("=== 回答质量抽检结束 ===");
        // 离线 CLI：评估完成后显式退出 JVM
        System.exit(0);
    }

    /** 回答质量护栏：空回答/本地降级 = 硬 FAIL；平均引用回退超 5% = 软告警 */
    private void checkBaseline(int total, int empty, int degraded, double avgRefs) {
        try {
            if (!Files.exists(BASELINE_PATH)) {
                log.warn("[GUARD] NO_BASELINE 未发现基线 {}，首次运行请加 --update-baseline 建立基线", BASELINE_PATH);
                return;
            }
            JsonNode base = objectMapper.readTree(Files.readString(BASELINE_PATH));
            double baseAvgRefs = base.path("avgRefs").asDouble(0);
            boolean hardFail = empty > 0 || degraded > 0;
            boolean softRegress = baseAvgRefs > 0 && avgRefs < baseAvgRefs * REGRESSION_THRESHOLD;
            if (hardFail || softRegress) {
                meterRegistry.counter("devmind.answer.regression").increment();
                log.error("[GUARD] FAIL 回答质量回退：空回答={}/{} 本地降级={}/{} 平均引用={}（基线 {}），请检查检索配置/知识库/模型链路",
                        empty, total, degraded, total, round(avgRefs), round(baseAvgRefs));
            } else {
                log.info("[GUARD] PASS 回答质量护栏通过：空回答={}/{} 本地降级={}/{} 平均引用={}（基线 {}）",
                        empty, total, degraded, total, round(avgRefs), round(baseAvgRefs));
            }
        } catch (Exception ex) {
            log.warn("基线对比失败: {}", ex.getMessage());
        }
    }

    /** 把当前结果写为基线（--update-baseline） */
    private void writeBaseline(int total, int empty, int degraded, double avgRefs) {
        try {
            Files.createDirectories(BASELINE_PATH.getParent());
            Files.writeString(BASELINE_PATH, """
                    {
                      "knowledgeBaseId": %d,
                      "total": %d,
                      "empty": %d,
                      "degraded": %d,
                      "avgRefs": %.4f,
                      "updatedAt": "%s"
                    }
                    """.formatted(
                    EVAL_KB_ID, total, empty, degraded, avgRefs, OffsetDateTime.now()));
            log.info("回答质量基线已写入 {}（平均引用={}）", BASELINE_PATH, round(avgRefs));
        } catch (Exception ex) {
            log.warn("基线写盘失败: {}", ex.getMessage());
        }
    }

    private String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
