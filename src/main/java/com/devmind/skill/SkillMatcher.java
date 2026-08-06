package com.devmind.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能匹配器（Guide-51 §5.2）：在 Agent 请求时，按关键词粗筛命中当前请求场景的
 * 团队/个人技能，返回需注入的规范文本。P1 用关键词粗筛（零成本），P3 升级为语义检索。
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

    private final SkillRepository repository;

    public SkillMatcher(SkillRepository repository) {
        this.repository = repository;
    }

    /** 匹配结果：关键词命中的注入全文 + 其余技能轻量清单（渐进披露） */
    public record MatchResult(List<String> injectFull, List<String> catalog) {
        public boolean isEmpty() {
            return (injectFull == null || injectFull.isEmpty())
                    && (catalog == null || catalog.isEmpty());
        }
    }

    /**
     * 匹配当前请求（Guide-51 P1 + P3 渐进披露）：
     * 1. 关键词命中（apply_to 包含问题中的词）→ 注入全文规范（确定性场景直接遵循）；
     * 2. 其余技能 → 只注入轻量清单（ID+名称+描述），模型判断相关时调 load_skill 获取全文。
     * 命中全文自增 hit_count（发现僵尸/热门技能）。
     */
    public MatchResult match(String question, Long tenantId, Long userId) {
        List<String> injectFull = new ArrayList<>();
        List<String> catalog = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return new MatchResult(injectFull, catalog);
        }
        try {
            List<Skill> skills = repository.listEnabledForUser(tenantId, userId);
            String q = question.toLowerCase();
            int catalogCount = 0;
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
                        repository.incrementHit(tenantId, skill.id());
                        continue;
                    }
                    // 已到全文上限：其余命中技能降级进清单（模型可 load_skill 加载）
                }
                if (catalogCount < MAX_CATALOG) {
                    // 未命中关键词（或命中但全文已满）：只给名称+描述，供模型按需加载全文
                    String desc = skill.description() == null || skill.description().isBlank()
                            ? skill.name() : skill.description();
                    catalog.add("【技能 ID " + skill.id() + "】" + skill.name() + "：" + desc);
                    catalogCount++;
                }
            }
        } catch (Exception ex) {
            log.warn("技能匹配失败，跳过注入: {}", ex.getMessage());
        }
        return new MatchResult(injectFull, catalog);
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
                long id = ref.path("id").asLong(0);
                String name = ref.path("name").asText("");
                if (type.isBlank() || id <= 0) {
                    continue;
                }
                if ("workflow".equals(type)) {
                    parts.add("【可联动资源：工作流「" + name + "」(ID " + id + ")，如需执行请调用 run_workflow】");
                } else if ("kb".equals(type)) {
                    parts.add("【可联动资源：知识库「" + name + "」(ID " + id + ")，如需检索请调用 kb_search 并指定该知识库】");
                }
            }
            return String.join("\n", parts);
        } catch (Exception ex) {
            log.warn("技能 references 解析失败: {}", ex.getMessage());
            return "";
        }
    }
}
