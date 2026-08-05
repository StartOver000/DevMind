package com.devmind.evaluation;

import com.devmind.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalMetricsCalculatorTest {

    private static RetrievalResult chunk(long id, String content) {
        return new RetrievalResult(id, 1L, "a.md", 0, content, Map.of("heading", "h"), 0.9);
    }

    @Test
    void perfectFirstHitGivesFullScores() {
        List<RetrievalResult> ranked = List.of(
                chunk(1, "包含关键字 deep pagination"),
                chunk(2, "无关内容"),
                chunk(3, "无关内容")
        );

        RetrievalMetricsCalculator.Metrics m = RetrievalMetricsCalculator.compute(
                ranked, r -> r.content().contains("deep pagination"));

        assertThat(m.mrr()).isEqualTo(1.0);
        assertThat(m.recall5()).isEqualTo(1.0);
        assertThat(m.recall10()).isEqualTo(1.0);
        assertThat(m.ndcg10()).isEqualTo(1.0);
    }

    @Test
    void thirdRankHitGivesMrrOneThird() {
        List<RetrievalResult> ranked = List.of(
                chunk(1, "无关"),
                chunk(2, "无关"),
                chunk(3, "含关键字 abc"),
                chunk(4, "含关键字 abc"),
                chunk(5, "无关")
        );

        RetrievalMetricsCalculator.Metrics m = RetrievalMetricsCalculator.compute(
                ranked, r -> r.content().contains("abc"));

        assertThat(m.mrr()).isEqualTo(1.0 / 3.0);
        // top5 内 2 个相关 / 共 2 个相关
        assertThat(m.recall5()).isEqualTo(1.0);
        assertThat(m.recall10()).isEqualTo(1.0);
    }

    @Test
    void noRelevantGivesZero() {
        List<RetrievalResult> ranked = List.of(chunk(1, "无关"), chunk(2, "无关"));

        RetrievalMetricsCalculator.Metrics m = RetrievalMetricsCalculator.compute(
                ranked, r -> r.content().contains("不存在"));

        assertThat(m.mrr()).isZero();
        assertThat(m.recall5()).isZero();
        assertThat(m.ndcg10()).isZero();
    }

    @Test
    void recallDifferentiatesTop5AndTop10() {
        // 3 个相关：第 3、7、9 位
        List<RetrievalResult> ranked = List.of(
                chunk(1, "无关"), chunk(2, "无关"), chunk(3, "含k"),
                chunk(4, "无关"), chunk(5, "无关"), chunk(6, "无关"), chunk(7, "含k"),
                chunk(8, "无关"), chunk(9, "含k"), chunk(10, "无关")
        );

        RetrievalMetricsCalculator.Metrics m = RetrievalMetricsCalculator.compute(
                ranked, r -> r.content().contains("k"));

        // top5 命中 1 个 / 共 3 个相关
        assertThat(m.recall5()).isEqualTo(1.0 / 3.0);
        // top10 命中 3 个 / 共 3 个相关
        assertThat(m.recall10()).isEqualTo(1.0);
        // MRR = 1/3
        assertThat(m.mrr()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void ndcgRewardsHigherRanks() {
        List<RetrievalResult> ranked = List.of(
                chunk(1, "含k"),
                chunk(2, "含k"),
                chunk(3, "含k"),
                chunk(4, "无关")
        );

        RetrievalMetricsCalculator.Metrics m = RetrievalMetricsCalculator.compute(
                ranked, r -> r.content().contains("k"));

        double dcg = 1 / Math.log(2) + 1 / Math.log(3) + 1 / Math.log(4);
        double idcg = 1 / Math.log(2) + 1 / Math.log(3) + 1 / Math.log(4);
        assertThat(m.ndcg10()).isEqualTo(dcg / idcg);
    }
}
