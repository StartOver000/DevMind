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
        double faithfulnessSum = 0;
        // 按知识库过滤：每个评估集只测对应知识库的题（2026-08-14 黄金评估集改造）
        List<EvaluationQuestion> kbQuestions = QUESTIONS.stream()
                .filter(q -> knowledgeBaseId.equals(q.knowledgeBaseId()))
                .toList();
        for (EvaluationQuestion question : kbQuestions) {
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
            // 生成质量近似（P2-5）：期望答案的关键事实是否被检索片段覆盖——生成有依据的比例
            faithfulnessSum += FaithfulnessScorer.score(
                    question.expected(),
                    top.stream().map(RetrievalResult::content).toList()
            );
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
        int total = kbQuestions.size();
        double hitRate = total == 0 ? 0 : (double) hits / total;
        return new RetrievalEvaluationResponse(
                total,
                hits,
                hitRate,
                total == 0 ? 0 : mrrSum / total,
                total == 0 ? 0 : recall5Sum / total,
                total == 0 ? 0 : recall10Sum / total,
                total == 0 ? 0 : ndcg10Sum / total,
                total == 0 ? 0 : faithfulnessSum / total,
                items,
                topics
        );
    }

    record EvaluationQuestion(Long knowledgeBaseId, String question, String expected, String topic) {
        /** 兼容旧 3 参构造：默认知识库 19（AI 八股） */
        EvaluationQuestion(String question, String expected, String topic) {
            this(19L, question, expected, topic);
        }
    }

    static final List<EvaluationQuestion> QUESTIONS = List.of(
            // ===== 知识库 19：AI 工程八股（JavaGuide-AI 专题，20 条；expected 均为库内真实关键词）=====
            new EvaluationQuestion(19L, "向量检索的原理是什么", "向量", "RAG检索"),
            new EvaluationQuestion(19L, "什么是 RAG", "RAG", "RAG检索"),
            new EvaluationQuestion(19L, "embedding 是什么", "Embedding", "RAG检索"),
            new EvaluationQuestion(19L, "混合检索有什么好处", "混合", "RAG检索"),
            new EvaluationQuestion(19L, "切块大小影响检索效果吗", "切块", "RAG检索"),
            new EvaluationQuestion(19L, "重排序能提升检索精度吗", "Rerank", "RAG检索"),
            new EvaluationQuestion(19L, "知识库文档怎么入库", "文档", "RAG检索"),
            new EvaluationQuestion(19L, "关键词检索和向量检索区别", "混合", "RAG检索"),
            new EvaluationQuestion(19L, "检索评估怎么看命中率", "评估", "RAG检索"),
            new EvaluationQuestion(19L, "RAG 怎么减少幻觉", "幻觉", "RAG优化"),
            new EvaluationQuestion(19L, "RAG 检索质量怎么评估", "评估", "RAG优化"),
            new EvaluationQuestion(19L, "Prompt 工程有哪些技巧", "Prompt", "Prompt工程"),
            new EvaluationQuestion(19L, "Agent 的记忆怎么设计", "记忆", "Agent"),
            new EvaluationQuestion(19L, "Function Calling 是什么", "Function", "Agent"),
            new EvaluationQuestion(19L, "结构化输出怎么实现", "结构化", "Agent"),
            new EvaluationQuestion(19L, "MCP 是什么", "MCP", "MCP"),
            new EvaluationQuestion(19L, "LLM 网关是什么", "网关", "LLM网关"),
            new EvaluationQuestion(19L, "LLM 评估有哪些指标", "评估", "LLM评估"),
            new EvaluationQuestion(19L, "GraphRAG 是什么", "GraphRAG", "GraphRAG"),
            new EvaluationQuestion(19L, "pgvector 支持哪些索引", "索引", "向量库"),
            // ===== 知识库 20：Java 后端八股（JavaGuide 精选，28 条；expected 均为库内真实关键词）=====
            new EvaluationQuestion(20L, "ThreadLocal 的原理是什么", "ThreadLocal", "Java并发"),
            new EvaluationQuestion(20L, "ThreadLocal 会内存泄漏吗", "ThreadLocal", "Java并发"),
            new EvaluationQuestion(20L, "synchronized 和 volatile 的区别", "synchronized", "Java并发"),
            new EvaluationQuestion(20L, "volatile 能保证原子性吗", "volatile", "Java并发"),
            new EvaluationQuestion(20L, "CAS 是什么", "CAS", "Java并发"),
            new EvaluationQuestion(20L, "AQS 是什么", "AQS", "Java并发"),
            new EvaluationQuestion(20L, "什么是死锁怎么避免", "死锁", "Java并发"),
            new EvaluationQuestion(20L, "线程池的核心参数有哪些", "线程池", "Java并发"),
            new EvaluationQuestion(20L, "线程池拒绝策略有哪些", "线程池", "Java并发"),
            new EvaluationQuestion(20L, "HashMap 的底层结构", "HashMap", "Java集合"),
            new EvaluationQuestion(20L, "ConcurrentHashMap 为什么线程安全", "ConcurrentHashMap", "Java集合"),
            new EvaluationQuestion(20L, "ArrayList 和 LinkedList 区别", "ArrayList", "Java集合"),
            new EvaluationQuestion(20L, "CopyOnWriteArrayList 的原理", "CopyOnWrite", "Java集合"),
            new EvaluationQuestion(20L, "JVM 内存区域有哪些", "JVM", "JVM"),
            new EvaluationQuestion(20L, "垃圾回收算法有哪些", "垃圾回收", "JVM"),
            new EvaluationQuestion(20L, "类加载过程是什么", "类加载", "JVM"),
            new EvaluationQuestion(20L, "双亲委派模型是什么", "双亲委派", "JVM"),
            new EvaluationQuestion(20L, "JMM 是什么", "JMM", "JVM"),
            new EvaluationQuestion(20L, "MySQL 索引为什么会失效", "索引", "数据库"),
            new EvaluationQuestion(20L, "什么情况下会走慢查询", "慢查询", "数据库"),
            new EvaluationQuestion(20L, "数据库事务隔离级别有哪些", "事务", "数据库"),
            new EvaluationQuestion(20L, "Redis 缓存穿透怎么解决", "Redis", "Redis"),
            new EvaluationQuestion(20L, "Redis 数据过期策略有哪些", "Redis", "Redis"),
            new EvaluationQuestion(20L, "Spring 事务失效场景有哪些", "事务", "Spring"),
            new EvaluationQuestion(20L, "Spring AOP 的实现原理", "AOP", "Spring"),
            new EvaluationQuestion(20L, "RabbitMQ 消息丢失怎么解决", "RabbitMQ", "消息队列"),
            new EvaluationQuestion(20L, "Kafka 为什么快", "Kafka", "消息队列"),
            new EvaluationQuestion(20L, "RocketMQ 事务消息原理", "RocketMQ", "消息队列")
    );
}
