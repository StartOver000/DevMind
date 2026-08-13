package com.devmind.config;

import com.devmind.ai.AiModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量重建维护能力（开关：devmind.rebuild-vectors-on-start=true）。
 *
 * 场景：mock 向量算法调整 / 更换 embedding 模型或维度后，库中历史 chunk 向量与当前
 * 网关算法不一致（表现为检索相似度骤降、命中为 0），需要按当前网关重新生成全量向量。
 *
 * 行为：分批读取全部 chunk → 调用当前 embed 网关重新向量化 → 批量回写
 * document_chunk.embedding → 清空 embedding_cache（query 侧缓存一并失效）。
 * 默认关闭；仅显式开启时在启动阶段执行（真实模型模式下会产生模型调用费用，慎用）。
 */
@Component
@ConditionalOnProperty(name = "devmind.rebuild-vectors-on-start", havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE)
public class VectorRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorRebuildRunner.class);
    /** 单批向量化条数（避免单次 embed 请求体过大） */
    private static final int BATCH_SIZE = 32;

    private final AiModelGateway modelGateway;
    private final JdbcTemplate jdbcTemplate;

    public VectorRebuildRunner(AiModelGateway modelGateway, JdbcTemplate jdbcTemplate) {
        this.modelGateway = modelGateway;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, content FROM document_chunk ORDER BY id");
        log.info("向量重建开始：共 {} 个 chunk", rows.size());

        int updated = 0;
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, rows.size());
            List<Long> ids = new ArrayList<>(to - from);
            List<String> contents = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                Map<String, Object> row = rows.get(i);
                ids.add(((Number) row.get("id")).longValue());
                contents.add(String.valueOf(row.get("content")));
            }
            List<List<Double>> vectors = modelGateway.embed(contents);
            for (int i = 0; i < ids.size(); i++) {
                jdbcTemplate.update(
                        "UPDATE document_chunk SET embedding = ?::vector WHERE id = ?",
                        toVector(vectors.get(i)),
                        ids.get(i));
            }
            updated += ids.size();
            log.info("向量重建进度：{}/{}", updated, rows.size());
        }

        jdbcTemplate.update("DELETE FROM embedding_cache");
        log.info("向量重建完成：更新 {} 个 chunk，已清空 embedding_cache（query 侧缓存）", updated);
    }

    private static String toVector(List<Double> vector) {
        return "[" + vector.stream()
                .map(v -> Double.toString(v))
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "]";
    }
}
