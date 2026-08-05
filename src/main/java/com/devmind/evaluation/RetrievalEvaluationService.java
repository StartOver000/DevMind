package com.devmind.evaluation;

import com.devmind.ai.AiModelGateway;
import com.devmind.config.DevMindProperties;
import com.devmind.evaluation.dto.EvaluationItem;
import com.devmind.evaluation.dto.EvaluationRequest;
import com.devmind.evaluation.dto.EvaluationTopicResult;
import com.devmind.evaluation.dto.RetrievalEvaluationResponse;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.retrieval.RerankService;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.user.UserService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RetrievalEvaluationService {

    private final UserService userService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AiModelGateway modelGateway;
    private final RetrievalService retrievalService;
    private final RerankService rerankService;
    private final ModelUsageService modelUsageService;
    private final DevMindProperties properties;

    public RetrievalEvaluationService(
            UserService userService,
            KnowledgeBaseService knowledgeBaseService,
            AiModelGateway modelGateway,
            RetrievalService retrievalService,
            RerankService rerankService,
            ModelUsageService modelUsageService,
            DevMindProperties properties
    ) {
        this.userService = userService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelGateway = modelGateway;
        this.retrievalService = retrievalService;
        this.rerankService = rerankService;
        this.modelUsageService = modelUsageService;
        this.properties = properties;
    }

    public RetrievalEvaluationResponse evaluate(EvaluationRequest request, Long userId) {
        return evaluate(request, userId, properties.retrievalVectorWeight(), properties.retrievalKeywordWeight());
    }

    /** 支持指定混合检索权重（供离线 α 寻优），其余逻辑与 {@link #evaluate(EvaluationRequest, Long)} 一致 */
    public RetrievalEvaluationResponse evaluate(
            EvaluationRequest request,
            Long userId,
            double vectorWeight,
            double keywordWeight
    ) {
        Long knowledgeBaseId = request.knowledgeBaseId();
        Map<String, Object> metadataFilter = request.tags() == null || request.tags().isEmpty()
                ? Map.of()
                : Map.of("tags", request.tags().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
        userService.requireUser(userId);
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);
        List<EvaluationItem> items = new ArrayList<>();
        Map<String, int[]> topicStats = new LinkedHashMap<>();
        double mrrSum = 0;
        double recall5Sum = 0;
        double recall10Sum = 0;
        double ndcg10Sum = 0;
        for (EvaluationQuestion question : QUESTIONS) {
            topicStats.computeIfAbsent(question.topic(), k -> new int[2]);
            topicStats.get(question.topic())[0]++;
            List<Double> vector = modelGateway.embed(List.of(question.question())).get(0);
            modelUsageService.record(userId, "evaluation", "embedding", null, null, question.question(), null);
            List<RetrievalResult> results = retrievalService.searchHybrid(
                    knowledgeBaseId,
                    vector,
                    question.question(),
                    10,
                    0.1,
                    vectorWeight,
                    keywordWeight,
                    properties.retrievalHybridEnabled(),
                    metadataFilter
            );
            List<RetrievalResult> top = rerankService.rerank(
                    question.question(),
                    results,
                    properties.evaluationTopK(),
                    request.rerankMode()
            );
            java.util.function.Predicate<RetrievalResult> relevant = result ->
                    result.content().contains(question.expected())
                            || result.documentName().contains(question.expected());
            boolean hit = top.stream().anyMatch(relevant);
            if (hit) {
                topicStats.get(question.topic())[1]++;
            }
            RetrievalMetricsCalculator.Metrics metrics = RetrievalMetricsCalculator.compute(top, relevant);
            mrrSum += metrics.mrr();
            recall5Sum += metrics.recall5();
            recall10Sum += metrics.recall10();
            ndcg10Sum += metrics.ndcg10();
            items.add(new EvaluationItem(
                    question.question(),
                    question.expected(),
                    hit,
                    top.size(),
                    top.stream().map(RetrievalResult::chunkId).toList()
            ));
        }
        int hits = topicStats.values().stream().mapToInt(v -> v[1]).sum();
        List<EvaluationTopicResult> topics = topicStats.entrySet().stream()
                .map(entry -> new EvaluationTopicResult(
                        entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        entry.getValue()[0] == 0 ? 0 : (double) entry.getValue()[1] / entry.getValue()[0]
                ))
                .toList();
        int total = questions().size();
        double hitRate = total == 0 ? 0 : (double) hits / total;
        return new RetrievalEvaluationResponse(
                total,
                hits,
                hitRate,
                total == 0 ? 0 : mrrSum / total,
                total == 0 ? 0 : recall5Sum / total,
                total == 0 ? 0 : recall10Sum / total,
                total == 0 ? 0 : ndcg10Sum / total,
                items,
                topics
        );
    }

    private List<EvaluationQuestion> questions() {
        return QUESTIONS;
    }

    record EvaluationQuestion(String question, String expected, String topic) {
    }

    static final List<EvaluationQuestion> QUESTIONS = List.of(
            // ===== MySQL 索引与深分页（20 条，覆盖 MySQL索引专题.md） =====
            new EvaluationQuestion("MySQL 深分页为什么会慢", "深分页", "MySQL索引"),
            new EvaluationQuestion("深分页怎么优化", "深分页", "MySQL索引"),
            new EvaluationQuestion("延迟关联是什么", "延迟关联", "MySQL索引"),
            new EvaluationQuestion("索引失效有哪些场景", "索引失效", "MySQL索引"),
            new EvaluationQuestion("左模糊查询为什么慢", "左模糊", "MySQL索引"),
            new EvaluationQuestion("对索引列使用函数会怎样", "索引", "MySQL索引"),
            new EvaluationQuestion("隐式类型转换导致什么问题", "索引", "MySQL索引"),
            new EvaluationQuestion("最左前缀原则是什么意思", "最左前缀", "MySQL索引"),
            new EvaluationQuestion("联合索引怎么用", "联合索引", "MySQL索引"),
            new EvaluationQuestion("EXPLAIN 看哪些字段", "执行计划", "MySQL索引"),
            new EvaluationQuestion("type=ALL 是什么问题", "全表扫描", "MySQL索引"),
            new EvaluationQuestion("Using filesort 怎么解决", "filesort", "MySQL索引"),
            new EvaluationQuestion("Using temporary 怎么优化", "临时表", "MySQL索引"),
            new EvaluationQuestion("覆盖索引有什么好处", "覆盖索引", "MySQL索引"),
            new EvaluationQuestion("order by 为什么慢", "索引", "MySQL索引"),
            new EvaluationQuestion("group by 为什么慢", "索引", "MySQL索引"),
            new EvaluationQuestion("join 太慢怎么排查", "执行计划", "MySQL索引"),
            new EvaluationQuestion("慢查询怎么定位", "执行计划", "MySQL索引"),
            new EvaluationQuestion("索引基数是什么", "索引", "MySQL索引"),
            new EvaluationQuestion("count 大表怎么优化", "索引", "MySQL索引"),
            // ===== SQL 优化场景（10 条） =====
            new EvaluationQuestion("LIMIT OFFSET 大偏移量为什么慢", "深分页", "SQL优化"),
            new EvaluationQuestion("游标分页怎么做", "深分页", "SQL优化"),
            new EvaluationQuestion("回表查询是什么", "索引", "SQL优化"),
            new EvaluationQuestion("前缀索引怎么用", "索引", "SQL优化"),
            new EvaluationQuestion("复合索引顺序怎么定", "联合索引", "SQL优化"),
            new EvaluationQuestion("排序为什么走 filesort", "filesort", "SQL优化"),
            new EvaluationQuestion("为什么会产生临时表", "临时表", "SQL优化"),
            new EvaluationQuestion("全表扫描什么时候会触发", "全表扫描", "SQL优化"),
            new EvaluationQuestion("如何避免 select 全列扫描", "覆盖索引", "SQL优化"),
            new EvaluationQuestion("建索引要考虑哪些因素", "索引", "SQL优化"),
            // ===== RAG 与检索（10 条，知识库平台能力） =====
            new EvaluationQuestion("向量检索的原理是什么", "向量", "RAG检索"),
            new EvaluationQuestion("混合检索有什么好处", "混合", "RAG检索"),
            new EvaluationQuestion("什么是 RAG", "RAG", "RAG检索"),
            new EvaluationQuestion("embedding 是什么", "Embedding", "RAG检索"),
            new EvaluationQuestion("切块大小影响检索效果吗", "切块", "RAG检索"),
            new EvaluationQuestion("重排序能提升检索精度吗", "Rerank", "RAG检索"),
            new EvaluationQuestion("知识库文档怎么入库", "文档", "RAG检索"),
            new EvaluationQuestion("检索相似度阈值怎么设置", "检索", "RAG检索"),
            new EvaluationQuestion("关键词检索和向量检索区别", "混合", "RAG检索"),
            new EvaluationQuestion("检索评估怎么看命中率", "评估", "RAG检索"),
            // ===== 运维与容量（10 条） =====
            new EvaluationQuestion("PostgreSQL 怎么建向量索引", "索引", "运维容量"),
            new EvaluationQuestion("pgvector 支持哪些索引", "索引", "运维容量"),
            new EvaluationQuestion("数据库备份怎么恢复", "备份", "运维容量"),
            new EvaluationQuestion("日志文件怎么按天切割", "日志", "运维容量"),
            new EvaluationQuestion("服务异常退出怎么自动重启", "重启", "运维容量"),
            new EvaluationQuestion("Nginx 反代怎么配 HTTPS", "Nginx", "运维容量"),
            new EvaluationQuestion("监控指标看哪些", "监控", "运维容量"),
            new EvaluationQuestion("数据库连接池满了怎么办", "连接", "运维容量"),
            new EvaluationQuestion("容器重启策略怎么配置", "重启", "运维容量"),
            new EvaluationQuestion("生产环境数据怎么备份", "备份", "运维容量")
    );
}
