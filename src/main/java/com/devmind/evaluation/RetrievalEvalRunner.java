package com.devmind.evaluation;

import com.devmind.ai.AiModelGateway;
import com.devmind.config.DevMindProperties;
import com.devmind.evaluation.dto.EvaluationRequest;
import com.devmind.evaluation.dto.RetrievalEvaluationResponse;
import com.devmind.retrieval.RerankService;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线检索评估 Runner：应用启动时带 {@code --eval} 参数触发。
 * 1) 用当前配置跑完整评估集，输出 MRR / Recall@5 / Recall@10 / NDCG@10 报告（含 JSON 落盘）。
 * 2) 检索质量护栏：与 {@code data/eval/baseline.json} 对比，MRR / Recall@10 回退超 5% 记
 *    {@code devmind.retrieval.regression} 指标并输出告警日志（首次用 {@code --update-baseline} 建立基线）。
 * 3) 混合检索权重 α 网格寻优（复用同一批向量，仅换权重），输出最优组合。
 */
@Component
public class RetrievalEvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalRunner.class);

    /** 评估使用的知识库（JavaGuide 专题库） */
    private static final long EVAL_KB_ID = 19L;
    /** 当前评估知识库：默认 19，可用 --kb=<id> 指定（如知识库 20 Java 八股） */
    private long evalKbId = EVAL_KB_ID;
    private static final double[] ALPHA_CANDIDATES = {0.1, 0.3, 0.5, 0.7, 0.9};
    /** 回退告警阈值：低于基线 5% 即告警 */
    private static final double REGRESSION_THRESHOLD = 0.95;

    private final RetrievalEvaluationService evaluationService;
    private final RetrievalService retrievalService;
    private final AiModelGateway modelGateway;
    private final RerankService rerankService;
    private final DevMindProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RetrievalEvalRunner(
            RetrievalEvaluationService evaluationService,
            RetrievalService retrievalService,
            AiModelGateway modelGateway,
            RerankService rerankService,
            DevMindProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.evaluationService = evaluationService;
        this.retrievalService = retrievalService;
        this.modelGateway = modelGateway;
        this.rerankService = rerankService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("eval")) {
            return;
        }
        if (args.containsOption("kb")) {
            List<String> v = args.getOptionValues("kb");
            if (v != null && !v.isEmpty()) {
                try {
                    evalKbId = Long.parseLong(v.get(0));
                } catch (NumberFormatException ex) {
                    log.warn("--kb 参数无效，使用默认知识库 {}: {}", EVAL_KB_ID, ex.getMessage());
                }
            }
        }
        log.info("=== 离线检索评估开始（知识库 {}）===", evalKbId);
        RetrievalEvaluationResponse report = evaluationService.evaluate(
                new EvaluationRequest(evalKbId, null, "heuristic"),
                1L
        );
        log.info("当前配置（vectorWeight={}）：total={} hitRate={} MRR={} Recall@5={} Recall@10={} NDCG@10={} Faithfulness={}",
                properties.retrievalVectorWeight(), report.total(), round(report.hitRate()),
                round(report.mrr()), round(report.recall5()), round(report.recall10()),
                round(report.ndcg10()), round(report.faithfulness()));
        // 逐题明细：诊断 Recall 漏召回（2026-08-15 召回分析）
        for (var item : report.items()) {
            log.info("EVAL_ITEM question={} expected={} hit={} topChunks={}",
                    item.question(), item.expectedKeyword(), item.hit(), item.chunkIds());
        }
        writeReport(report);
        if (args.containsOption("update-baseline")) {
            writeBaseline(report);
        } else {
            checkBaseline(report);
        }
        runAlphaSearch();
        log.info("=== 离线检索评估结束 ===");
        // 离线 CLI：评估完成后显式退出 JVM（Spring 调度线程为非 daemon，否则 docker compose run 永不退出）
        System.exit(0);
    }

    /** 检索质量护栏：与基线对比，MRR / Recall@10 回退超 5% 记 devmind.retrieval.regression 并告警 */
    private void checkBaseline(RetrievalEvaluationResponse report) {
        try {
            Path base = Path.of("data/eval/baseline.json");
            if (!Files.exists(base)) {
                log.warn("[GUARD] NO_BASELINE 未发现基线 data/eval/baseline.json，首次运行请加 --update-baseline 建立基线");
                return;
            }
            JsonNode baseline = objectMapper.readTree(Files.readString(base));
            double baseMrr = baseline.path("mrr").asDouble(0);
            double baseRecall10 = baseline.path("recall10").asDouble(0);
            double baseFaithfulness = baseline.path("faithfulness").asDouble(0);
            boolean regressed = (baseMrr > 0 && report.mrr() < baseMrr * REGRESSION_THRESHOLD)
                    || (baseRecall10 > 0 && report.recall10() < baseRecall10 * REGRESSION_THRESHOLD)
                    || (baseFaithfulness > 0 && report.faithfulness() < baseFaithfulness * REGRESSION_THRESHOLD);
            if (regressed) {
                meterRegistry.counter("devmind.retrieval.regression").increment();
                log.error("[GUARD] FAIL 检索/生成质量回退：MRR={}（基线 {}）Recall@10={}（基线 {}）Faithfulness={}（基线 {}），请检查检索配置或知识库变更",
                        round(report.mrr()), baseMrr, round(report.recall10()), baseRecall10,
                        round(report.faithfulness()), baseFaithfulness);
            } else {
                log.info("[GUARD] PASS 检索/生成质量护栏通过：MRR={}（基线 {}），Recall@10={}（基线 {}），Faithfulness={}（基线 {}）",
                        round(report.mrr()), baseMrr, round(report.recall10()), baseRecall10,
                        round(report.faithfulness()), baseFaithfulness);
            }
        } catch (Exception ex) {
            log.warn("基线对比失败: {}", ex.getMessage());
        }
    }

    /** 把当前报告写为基线（--update-baseline） */
    private void writeBaseline(RetrievalEvaluationResponse report) {
        try {
            Path dir = Path.of("data/eval");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("baseline.json"), """
                    {
                      "knowledgeBaseId": %d,
                      "total": %d,
                      "hits": %d,
                      "hitRate": %.4f,
                      "mrr": %.4f,
                      "recall5": %.4f,
                      "recall10": %.4f,
                      "ndcg10": %.4f,
                      "faithfulness": %.4f,
                      "vectorWeight": %s,
                      "updatedAt": "%s"
                    }
                    """.formatted(
                    EVAL_KB_ID,
                    report.total(),
                    report.hits(),
                    report.hitRate(),
                    report.mrr(),
                    report.recall5(),
                    report.recall10(),
                    report.ndcg10(),
                    report.faithfulness(),
                    properties.retrievalVectorWeight(),
                    OffsetDateTime.now()
            ));
            log.info("检索质量基线已写入 data/eval/baseline.json（MRR={}）", round(report.mrr()));
        } catch (Exception ex) {
            log.warn("基线写盘失败: {}", ex.getMessage());
        }
    }

    /** α 网格寻优：同一批问题只 embed 一次，换混合权重跑检索，取 MRR 最优 */
    private void runAlphaSearch() {
        // 只评估当前知识库的题（2026-08-14 黄金评估集改造：避免其他知识库的题稀释 MRR 导致 α 恒等假象）
        List<RetrievalEvaluationService.EvaluationQuestion> questions =
                RetrievalEvaluationService.QUESTIONS.stream()
                        .filter(q -> q.knowledgeBaseId() == evalKbId)
                        .toList();
        Map<String, List<Double>> vectorCache = new HashMap<>();
        for (RetrievalEvaluationService.EvaluationQuestion q : questions) {
            try {
                vectorCache.put(q.question(), modelGateway.embed(List.of(q.question())).get(0));
            } catch (Exception ex) {
                log.warn("embedding 失败（问题：{}）: {}", q.question(), ex.getMessage());
            }
        }
        Map<String, Double> mrrByAlpha = new LinkedHashMap<>();
        double bestMrr = -1;
        double bestAlpha = properties.retrievalVectorWeight();
        for (double alpha : ALPHA_CANDIDATES) {
            double mrrSum = 0;
            int n = 0;
            for (RetrievalEvaluationService.EvaluationQuestion q : questions) {
                List<Double> vector = vectorCache.get(q.question());
                if (vector == null) {
                    continue;
                }
                List<RetrievalResult> results = retrievalService.searchHybrid(
                        evalKbId,
                        vector,
                        q.question(),
                        10,
                        0.1,
                        alpha,
                        1 - alpha,
                        properties.retrievalHybridEnabled(),
                        Map.of()
                );
                List<RetrievalResult> top = rerankService.rerank(q.question(), results, properties.evaluationTopK(), "heuristic");
                java.util.function.Predicate<RetrievalResult> relevant = r -> {
                    String content = r.content() == null ? "" : r.content().toLowerCase();
                    String name = r.documentName() == null ? "" : r.documentName().toLowerCase();
                    String exp = q.expected() == null ? "" : q.expected().toLowerCase();
                    return java.util.Arrays.stream(exp.split("\\|"))
                            .filter(e -> !e.isBlank())
                            .anyMatch(e -> content.contains(e) || name.contains(e));
                };
                mrrSum += RetrievalMetricsCalculator.compute(top, relevant).mrr();
                n++;
            }
            double mrr = n == 0 ? 0 : mrrSum / n;
            mrrByAlpha.put(String.valueOf(alpha), round(mrr));
            log.info("α（vectorWeight）={} → MRR={}", alpha, round(mrr));
            if (mrr > bestMrr) {
                bestMrr = mrr;
                bestAlpha = alpha;
            }
        }
        log.info("混合检索权重寻优：最优 vectorWeight={}（MRR={}）；当前配置 vectorWeight={}",
                bestAlpha, round(bestMrr), properties.retrievalVectorWeight());
        log.info("α 扫描结果：{}", mrrByAlpha);
    }

    private void writeReport(RetrievalEvaluationResponse report) {
        try {
            Path dir = Path.of("data/eval");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.json"), """
                    {
                      "knowledgeBaseId": %d,
                      "total": %d,
                      "hits": %d,
                      "hitRate": %.4f,
                      "mrr": %.4f,
                      "recall5": %.4f,
                      "recall10": %.4f,
                      "ndcg10": %.4f,
                      "vectorWeight": %s
                    }
                    """.formatted(
                    EVAL_KB_ID,
                    report.total(),
                    report.hits(),
                    report.hitRate(),
                    report.mrr(),
                    report.recall5(),
                    report.recall10(),
                    report.ndcg10(),
                    properties.retrievalVectorWeight()
            ));
            log.info("评估报告已写入 data/eval/report.json");
        } catch (Exception ex) {
            log.warn("评估报告写盘失败: {}", ex.getMessage());
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
