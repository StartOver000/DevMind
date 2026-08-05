package com.devmind.evaluation;

import com.devmind.ai.AiModelGateway;
import com.devmind.config.DevMindProperties;
import com.devmind.evaluation.dto.EvaluationRequest;
import com.devmind.evaluation.dto.RetrievalEvaluationResponse;
import com.devmind.retrieval.RerankService;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线检索评估 Runner：应用启动时带 {@code --eval} 参数触发。
 * 1) 用当前配置跑完整评估集，输出 MRR / Recall@5 / Recall@10 / NDCG@10 报告（含 JSON 落盘）。
 * 2) 混合检索权重 α 网格寻优（复用同一批向量，仅换权重），输出最优组合。
 */
@Component
public class RetrievalEvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalRunner.class);

    /** 评估使用的知识库（JavaGuide 专题库） */
    private static final long EVAL_KB_ID = 19L;
    private static final double[] ALPHA_CANDIDATES = {0.1, 0.3, 0.5, 0.7, 0.9};

    private final RetrievalEvaluationService evaluationService;
    private final RetrievalService retrievalService;
    private final AiModelGateway modelGateway;
    private final RerankService rerankService;
    private final DevMindProperties properties;

    public RetrievalEvalRunner(
            RetrievalEvaluationService evaluationService,
            RetrievalService retrievalService,
            AiModelGateway modelGateway,
            RerankService rerankService,
            DevMindProperties properties
    ) {
        this.evaluationService = evaluationService;
        this.retrievalService = retrievalService;
        this.modelGateway = modelGateway;
        this.rerankService = rerankService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("eval")) {
            return;
        }
        log.info("=== 离线检索评估开始（知识库 {}）===", EVAL_KB_ID);
        RetrievalEvaluationResponse report = evaluationService.evaluate(
                new EvaluationRequest(EVAL_KB_ID, null, "heuristic"),
                1L
        );
        log.info("当前配置（vectorWeight={}）：total={} hitRate={} MRR={} Recall@5={} Recall@10={} NDCG@10={}",
                properties.retrievalVectorWeight(), report.total(), round(report.hitRate()),
                round(report.mrr()), round(report.recall5()), round(report.recall10()), round(report.ndcg10()));
        writeReport(report);
        runAlphaSearch();
        log.info("=== 离线检索评估结束 ===");
    }

    /** α 网格寻优：同一批问题只 embed 一次，换混合权重跑检索，取 MRR 最优 */
    private void runAlphaSearch() {
        List<RetrievalEvaluationService.EvaluationQuestion> questions = RetrievalEvaluationService.QUESTIONS;
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
                        EVAL_KB_ID,
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
                java.util.function.Predicate<RetrievalResult> relevant = r ->
                        r.content().contains(q.expected()) || r.documentName().contains(q.expected());
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
