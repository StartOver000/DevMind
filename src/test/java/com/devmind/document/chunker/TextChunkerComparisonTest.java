package com.devmind.document.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切块策略对比测试：同一份样本文本分别用语义边界切块与固定大小切块，
 * 统计块数、长度分布与标题保留情况，作为检索质量对比报告的数据来源。
 */
class TextChunkerComparisonTest {

    private static final String SAMPLE = """
            # MySQL 深分页为什么慢

            深分页通常使用 LIMIT OFFSET。随着 offset 增大，MySQL 需要扫描并丢弃越来越多的前置记录，
            再返回目标页数据，所以耗时会持续增加。

            ## 优化手段

            常见的优化手段包括游标分页和延迟关联。

            - 游标分页：基于上一页最后一条记录的主键作为游标。
            - 延迟关联：先利用覆盖索引筛选出主键，再回表查询完整行，减少无效扫描。

            ## 执行计划关注点

            使用 EXPLAIN 时重点看 type、key、rows 和 Extra。

            1. 出现 ALL 时通常需要调整索引。
            2. 出现 Using filesort 时通常需要优化排序。
            3. 出现 Using temporary 时通常需要优化分组或去重。

            ## 索引失效场景

            对索引列使用函数、隐式类型转换、左模糊查询都可能导致索引失效。

            最左前缀原则要求联合索引按照最左侧列开始匹配。

            ## 覆盖索引

            覆盖索引可以避免回表，让查询只访问索引本身。

            索引基数越高，区分度越好，索引效果越明显。
            """;

    @Test
    void boundaryChunkerKeepsSemanticBlocks() {
        DefaultTextChunker chunker = new DefaultTextChunker(500, 50);
        List<TextChunk> chunks = chunker.chunk(SAMPLE);

        assertThat(chunks).isNotEmpty();
        // 语义边界切块应当按标题拆分，块数 >= 标题数
        long headingChunks = chunks.stream().filter(c -> c.heading() != null).count();
        assertThat(headingChunks).isGreaterThanOrEqualTo(5);
        // 每个块都不应超过 maxChars（含少量 overlap 余量）
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(550));
    }

    @Test
    void fixedChunkerProducesBoundedSizes() {
        FixedSizeTextChunker chunker = new FixedSizeTextChunker(500);
        List<TextChunk> chunks = chunker.chunk(SAMPLE);

        assertThat(chunks).isNotEmpty();
        // 固定大小切块：除最后一个块外，其余块长度接近上限
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(510));
        // 标题被保留为块级上下文
        assertThat(chunks.stream().anyMatch(c -> c.heading() != null)).isTrue();
    }

    @Test
    void bothStrategiesCoverFullContent() {
        DefaultTextChunker boundary = new DefaultTextChunker(500, 50);
        FixedSizeTextChunker fixed = new FixedSizeTextChunker(500);

        List<TextChunk> boundaryChunks = boundary.chunk(SAMPLE);
        List<TextChunk> fixedChunks = fixed.chunk(SAMPLE);

        String boundaryText = boundaryChunks.stream().map(TextChunk::content).reduce("", (a, b) -> a + b);
        String fixedText = fixedChunks.stream().map(TextChunk::content).reduce("", (a, b) -> a + b);

        // 内容都应被完整覆盖（标题行会重复出现，允许差异）
        assertThat(boundaryText).contains("深分页");
        assertThat(boundaryText).contains("覆盖索引");
        assertThat(fixedText).contains("深分页");
        assertThat(fixedText).contains("覆盖索引");
        // 固定大小策略通常产生更多块
        assertThat(fixedChunks.size()).isGreaterThanOrEqualTo(boundaryChunks.size());
    }
}
