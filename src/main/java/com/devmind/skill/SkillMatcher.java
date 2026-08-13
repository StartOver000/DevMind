package com.devmind.skill;

import com.devmind.ai.AiModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能匹配器（Guide-51 §5.2）：在 Agent 请求时匹配当前请求场景的团队/个人技能。
 * 双通道（P1 关键词 + P3 语义精排）：
 * 1. 关键词命中（apply_to 包含问题中的词）→ 注入全文规范（确定性场景直接遵循）；
 * 2. 其余技能 → 轻量清单（渐进披露），有 embedding 时按语义相关度排序并标记"语义相关"，
 *    模型判断相关时调 load_skill 获取全文。
 * 语义链路不可用（embedding 失败/超时）自动降级为纯关键词，不影响主流程。
 */
@Component
public class SkillMatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillMatcher.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 单次最多注入全文的技能数（关键词命中） */
    private static final int MAX_INJECT = 2;
    /** 清单中最多展示的技能数（渐进披露：未命中仅给名称+描述，模型按需 load_skill） */
    private static final int MAX_CATALOG = 20;
    /** 单个技能内容截断（防上下文膨胀） */
    private static final int MAX_CONTENT_CHARS = 1500;
    /** 语义相关阈值：question vs skill 文本余弦相似度 ≥ 此值标记为语义相关 */
    private static final double SEMANTIC_THRESHOLD = 0.35;

    private final SkillRepository repository;
    /** 语义精排（P3）：可选注入，无 embedding 时退化为纯关键词匹配 */
    private AiModelGateway modelGateway;

    public SkillMatcher(SkillRepository repository) {
        this.repository = repository;
    }

    /** 语义精排网关（embedding）：可选注入，测试/无 AI 时为空 */
    @Autowired(required = false)
    public void setModelGateway(AiModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    /** 匹配结果：关键词命中的注入全文 + 其余技能轻量清单（渐进披露）+ 命中技能引用的接口工具 */
    public record MatchResult(
            List<String> injectFull,
            List<String> catalog,
            Set<String> linkedInterfaceTools
    ) {
        public MatchResult(List<String> injectFull, List<String> catalog) {
            this(injectFull, catalog, Set.of());
        }

        public boolean isEmpty() {
            return (injectFull == null || injectFull.isEmpty())
                    && (catalog == null || catalog.isEmpty());
        }
    }

    /**
     * 匹配当前请求（Guide-51 P1 + P3 语义精排）：
     * 1. 关键词命中（apply_to 包含问题中的词）→ 注入全文规范（确定性场景直接遵循）；
     * 2. 其余技能 → 轻量清单（ID+名称+描述），有 embedding 时按语义相关度排序并标记。
     * 命中全文自增 hit_count（发现僵尸/热门技能）。
     */
    public MatchResult match(String question, Long tenantId, Long userId) {
        List<String> injectFull = new ArrayList<>();
        List<Skill> remaining = new ArrayList<>();
        Set<String> linkedInterfaceTools = new java.util.LinkedHashSet<>();
        if (question == null || question.isBlank()) {
            return new MatchResult(injectFull, new ArrayList<>(), linkedInterfaceTools);
        }
        try {
            List<Skill> skills = repository.listEnabledForUser(tenantId, userId);
            String q = question.toLowerCase();
            for (Skill skill : skills) {
                if (matches(skill, q)) {
                    if (injectFull.size() < MAX_INJECT) {
                        String content = skill.content();
                        if (content.length() > MAX_CONTENT_CHARS) {
                            content = content.substring(0, MAX_CONTENT_CHARS);
                        }
                        String refs = formatReferences(skill.references());
                        String inject = "【技能 ID " + skill.id() + "：" + skill.name() + "】\n" + content;
                        if (!refs.isEmpty()) {
                            inject += "\n\n" + refs;
                        }
                        injectFull.add(inject);
                        // M4：收集命中技能引用的接口工具，供 Agent 直接注入工具列表
                        collectInterfaceTools(skill.references(), linkedInterfaceTools);
                        repository.incrementHit(tenantId, skill.id());
                        continue;
                    }
                    // 已到全文上限：其余命中技能降级进清单（模型可 load_skill 加载）
                }
                remaining.add(skill);
            }
        } catch (Exception ex) {
            log.warn("技能匹配失败，跳过注入: {}", ex.getMessage());
        }
        List<String> catalog = buildCatalog(question, remaining);
        return new MatchResult(injectFull, catalog, linkedInterfaceTools);
    }

    /**
     * 构建渐进披露清单：有 embedding 时按 question vs 技能文本（name+apply_to+description）
     * 余弦相似度降序，≥ 阈值标记"语义相关"；embedding 不可用/失败时按原顺序（不标记）。
     */
    private List<String> buildCatalog(String question, List<Skill> remaining) {
        List<String> catalog = new ArrayList<>();
        if (remaining.isEmpty()) {
            return catalog;
        }
        List<Skill> ordered = remaining;
        Map<Long, Double> scores = new java.util.HashMap<>();
        try {
            if (modelGateway != null && question != null && !question.isBlank()) {
                ordered = semanticRank(question, remaining, scores);
            }
        } catch (Exception ex) {
            // 语义链路不可用：降级为原顺序（关键词匹配仍生效，不影响主流程）
            log.warn("技能语义精排失败，降级为关键词匹配: {}", ex.getMessage());
            ordered = remaining;
        }
        int catalogCount = 0;
        for (Skill skill : ordered) {
            if (catalogCount >= MAX_CATALOG) {
                break;
            }
            String desc = skill.description() == null || skill.description().isBlank()
                    ? skill.name() : skill.description();
            String entry = "【技能 ID " + skill.id() + "】" + skill.name() + "：" + desc;
            // 语义相关标记（P3）：助模型优先 load_skill 判断
            Double score = scores.get(skill.id());
            if (score != null && score >= SEMANTIC_THRESHOLD) {
                entry += "（语义相关 " + String.format("%.0f", score * 100) + "%）";
            }
            catalog.add(entry);
            catalogCount++;
        }
        if (!scores.isEmpty()) {
            // 可观测：语义精排结果（问题 → 各技能相似度），便于评估匹配质量（Guide-51 P3）
            log.info("技能语义精排: question={}, scores={}", question,
                    scores.entrySet().stream()
                            .map(e -> e.getKey() + "=" + String.format("%.2f", e.getValue()))
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        return catalog;
    }

    /**
     * 语义精排：一次 embed 调用计算 question + 每个技能文本的向量，返回按相似度降序的技能列表。
     * 技能文本 = name + apply_to + description（紧凑、聚焦触发场景）。
     */
    private List<Skill> semanticRank(String question, List<Skill> skills, Map<Long, Double> scores) {
        List<String> texts = new ArrayList<>();
        texts.add(question);
        for (Skill s : skills) {
            texts.add(skillText(s));
        }
        List<List<Double>> vectors = modelGateway.embed(texts);
        if (vectors == null || vectors.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回数量不匹配");
        }
        double[] qv = toArray(vectors.get(0));
        for (int i = 0; i < skills.size(); i++) {
            double score = cosine(qv, toArray(vectors.get(i + 1)));
            scores.put(skills.get(i).id(), score);
        }
        return skills.stream()
                .sorted(Comparator.comparingDouble(
                        (Skill s) -> scores.getOrDefault(s.id(), 0.0)).reversed())
                .toList();
    }

    /** 技能语义文本：name + apply_to + description（拼接，供语义比较） */
    private String skillText(Skill skill) {
        StringBuilder sb = new StringBuilder();
        if (skill.name() != null) sb.append(skill.name()).append(' ');
        if (skill.applyTo() != null) sb.append(skill.applyTo()).append(' ');
        if (skill.description() != null) sb.append(skill.description());
        return sb.toString().trim();
    }

    private double[] toArray(List<Double> vector) {
        double[] arr = new double[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            arr[i] = vector.get(i) == null ? 0.0 : vector.get(i);
        }
        return arr;
    }

    /** 余弦相似度（向量应为归一化或原始均可，余弦本身归一化） */
    private double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalStateException("向量维度不一致");
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** apply_to 用 | 分隔多个关键词/场景；任一命中即匹配；空 apply_to 不匹配 */
    private boolean matches(Skill skill, String lowerQuestion) {
        String applyTo = skill.applyTo();
        if (applyTo == null || applyTo.isBlank()) {
            return false;
        }
        for (String token : applyTo.split("\\|")) {
            String t = token.trim().toLowerCase();
            if (!t.isEmpty() && lowerQuestion.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 按需加载（load_skill 内部工具）命中时自增 hit_count，反映真实使用热度 */
    public void recordLoad(Long tenantId, Long skillId) {
        try {
            repository.incrementHit(tenantId, skillId);
        } catch (Exception ex) {
            log.warn("技能命中统计失败: {}", ex.getMessage());
        }
    }

    /**
     * 把技能的 references（引用资源 JSON）格式化为可读联动说明，供模型按需执行。
     * 例：{"type":"workflow","id":3,"name":"监控日报"} → 【可联动资源：工作流 监控日报(ID 3)，如需执行调用 run_workflow】
     * 例：{"type":"kb","id":2,"name":"MySQL知识库"} → 【可联动资源：知识库 MySQL知识库(ID 2)，如需检索调用 kb_search 并指定该库】
     * 解析失败/空引用返回空串（不中断注入）。
     */
    private String formatReferences(String referencesJson) {
        if (referencesJson == null || referencesJson.isBlank() || "[]".equals(referencesJson.trim())) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(referencesJson);
            if (!root.isArray() || root.isEmpty()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            for (JsonNode ref : root) {
                String type = ref.path("type").asText("");
                if (type.isBlank()) {
                    continue;
                }
                long id = ref.path("id").asLong(0);
                String name = ref.path("name").asText("");
                if ("workflow".equals(type)) {
                    if (id <= 0) {
                        continue;
                    }
                    parts.add("【可联动资源：工作流「" + name + "」(ID " + id + ")，如需执行请调用 run_workflow】");
                } else if ("kb".equals(type)) {
                    if (id <= 0) {
                        continue;
                    }
                    parts.add("【可联动资源：知识库「" + name + "」(ID " + id + ")，如需检索请调用 kb_search 并指定该知识库】");
                } else if ("interface_tool".equals(type)) {
                    if (name.isBlank()) {
                        continue;
                    }
                    parts.add("【可联动资源：接口「" + name + "」，如需调用请直接使用该接口工具】");
                }
            }
            return String.join("\n", parts);
        } catch (Exception ex) {
            log.warn("技能 references 解析失败: {}", ex.getMessage());
            return "";
        }
    }

    /** 收集 references 中的接口工具名（M4 沉淀复用：技能声明的接口依赖直接可用） */
    private void collectInterfaceTools(String referencesJson, Set<String> out) {
        if (referencesJson == null || referencesJson.isBlank() || "[]".equals(referencesJson.trim())) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(referencesJson);
            if (!root.isArray()) {
                return;
            }
            for (JsonNode ref : root) {
                if ("interface_tool".equals(ref.path("type").asText(""))) {
                    String name = ref.path("name").asText("");
                    if (!name.isBlank()) {
                        out.add(name);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("技能接口工具引用解析失败: {}", ex.getMessage());
        }
    }
}
