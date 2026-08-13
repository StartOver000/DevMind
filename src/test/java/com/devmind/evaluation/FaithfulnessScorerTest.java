package com.devmind.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成质量 Faithfulness（P2-5）单测：忠于片段 vs 编造事实。
 */
class FaithfulnessScorerTest {

    @Test
    void faithfulAnswerScoresHigh() {
        String answer = "深分页慢的原因是全表扫描，耗时达到 500ms";
        List<String> contexts = List.of(
                "深分页慢的根因是执行计划走了全表扫描，耗时 500ms 以上"
        );
        double score = FaithfulnessScorer.score(answer, contexts);
        // 数字 500ms/500 与核心词都出现在片段里 → 高忠实
        assertThat(score).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void hallucinatedNumberScoresLow() {
        String answer = "优化后查询耗时降到 20ms，吞吐提升 8 倍";
        List<String> contexts = List.of(
                "优化后耗时降到 80ms，吞吐提升 2 倍"
        );
        double score = FaithfulnessScorer.score(answer, contexts);
        // 20、8 等关键事实不在片段 → 明显低于忠实场景（数字未命中拉低分数）
        assertThat(score).isLessThan(0.6);
    }

    @Test
    void hallucinatedNumberNotSupportedByEvidence() {
        String answer = "数据库连接池优化后，QPS 达到 5000";
        List<String> contexts = List.of(
                "通过缓存优化，检索 QPS 达到 183"
        );
        double score = FaithfulnessScorer.score(answer, contexts);
        // "5000" 不在片段（真实是 183）→ 该事实不忠实
        assertThat(score).isLessThan(0.9);
    }

    @Test
    void noClaimedFactsIsFaithful() {
        // 答案无数字/无实义英文词 → 无断言即无不忠（1.0）
        assertThat(FaithfulnessScorer.score("好的，我明白了", List.of("任意片段"))).isEqualTo(1.0);
        assertThat(FaithfulnessScorer.score(null, List.of())).isEqualTo(1.0);
        assertThat(FaithfulnessScorer.score("", List.of())).isEqualTo(1.0);
    }
}
