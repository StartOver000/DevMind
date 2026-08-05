package com.devmind.evaluation;

import com.devmind.retrieval.RetrievalResult;

import java.util.List;
import java.util.function.Predicate;

/**
 * 检索质量指标计算（纯函数，离线评估与在线评估共用）：
 * - MRR@10：第一个相关结果的倒数排名（未命中为 0）
 * - Recall@5 / Recall@10：top-K 内相关结果占比（分母为检索结果中相关总数，近似召回）
 * - NDCG@10：相关度二值（相关=1）的归一化折损累计增益
 *
 * 相关判定由调用方通过 {@link Predicate} 注入（当前用 expected 子串匹配作为弱标注）。
 */
public final class RetrievalMetricsCalculator {

    public record Metrics(double mrr, double recall5, double recall10, double ndcg10) {
    }

    private RetrievalMetricsCalculator() {
    }

    public static Metrics compute(List<RetrievalResult> ranked, Predicate<RetrievalResult> isRelevant) {
        int totalRelevant = (int) ranked.stream().filter(isRelevant).count();
        double mrr = 0;
        double recall5 = 0;
        double recall10 = 0;
        double dcg = 0;
        for (int i = 0; i < ranked.size(); i++) {
            boolean rel = isRelevant.test(ranked.get(i));
            int rank = i + 1;
            if (rel) {
                if (mrr == 0) {
                    mrr = 1.0 / rank;
                }
                if (rank <= 5) {
                    recall5++;
                }
                if (rank <= 10) {
                    recall10++;
                }
                dcg += 1.0 / Math.log(rank + 1);
            }
        }
        double denom = Math.max(1, totalRelevant);
        int ideal = Math.min(totalRelevant, 10);
        double idcg = 0;
        for (int i = 0; i < ideal; i++) {
            idcg += 1.0 / Math.log(i + 2);
        }
        return new Metrics(
                mrr,
                Math.min(1.0, recall5 / denom),
                Math.min(1.0, recall10 / denom),
                idcg == 0 ? 0 : dcg / idcg
        );
    }
}
