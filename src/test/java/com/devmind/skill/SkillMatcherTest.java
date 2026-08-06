package com.devmind.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillMatcherTest {

    private Skill skill(Long id, String scope, String name, String applyTo, String content) {
        return new Skill(id, 1L, scope, name, "desc", applyTo, content, "[]",
                "manual", null, true, 0L, 1L, null);
    }

    private Skill skill(Long id, String scope, String name, String description,
                        String applyTo, String content) {
        return new Skill(id, 1L, scope, name, description, applyTo, content, "[]",
                "manual", null, true, 0L, 1L, null);
    }

    private Skill skillWithRefs(Long id, String scope, String name, String description,
                                String applyTo, String content, String references) {
        return new Skill(id, 1L, scope, name, description, applyTo, content, references,
                "manual", null, true, 0L, 1L, null);
    }

    @Test
    void injectsReferencesIntoMatchedSkill() {
        // 命中技能带引用资源（工作流 + 知识库）→ 注入文本附带可联动说明（Guide-52 #3）
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skillWithRefs(1L, "team", "监控日报规范", "监控日报生成规范", "监控|日报",
                        "生成监控日报时必须总结版本信息。",
                        "[{\"type\":\"workflow\",\"id\":3,\"name\":\"定时监控日报\"},{\"type\":\"kb\",\"id\":2,\"name\":\"MySQL知识库\"}]")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("生成一份监控日报", 1L, 1L);

        assertThat(result.injectFull()).hasSize(1);
        String inject = result.injectFull().get(0);
        assertThat(inject).contains("监控日报规范").contains("生成监控日报时必须总结版本信息");
        // 引用资源以可联动说明注入（供模型 run_workflow / kb_search 联动）
        assertThat(inject).contains("可联动资源").contains("定时监控日报")
                .contains("run_workflow").contains("MySQL知识库").contains("kb_search");
        verify(repository).incrementHit(1L, 1L);
    }

    @Test
    void emptyOrInvalidReferencesProduceNoLinkage() {
        // 引用为空 / 非 JSON / 结构缺失 → 注入不带联动说明，不报错
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skillWithRefs(1L, "team", "规范A", "d", "报告", "内容A", "[]"),
                skillWithRefs(2L, "team", "规范B", "d", "报告", "内容B", "not-json"),
                skillWithRefs(3L, "team", "规范C", "d", "报告", "内容C",
                        "[{\"type\":\"unknown\",\"id\":1,\"name\":\"x\"}]")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("写报告", 1L, 1L);

        assertThat(result.injectFull()).hasSize(2); // 上限 2
        for (String inject : result.injectFull()) {
            assertThat(inject).doesNotContain("可联动资源");
        }
    }

    @Test
    void matchesQuestionByApplyToKeyword() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "月报规范", "月报|经营分析|月度报告", "生成月报必须包含同比环比。")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("帮我写一份月度经营分析报告", 1L, 1L);

        assertThat(result.injectFull()).hasSize(1);
        assertThat(result.injectFull().get(0)).contains("月报规范").contains("同比环比");
        // 注入文本带技能 ID（供 update_skill 定位）
        assertThat(result.injectFull().get(0)).contains("技能 ID 1");
        // 命中自增
        verify(repository).incrementHit(1L, 1L);
    }

    @Test
    void noKeywordHitGoesToCatalogInsteadOfFullText() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "月报规范", "生成月报规范，含同比环比。", "月报|经营分析", "生成月报必须包含同比环比。")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("今天天气怎么样", 1L, 1L);

        // 未命中：不进全文注入，也不自增 hit_count
        assertThat(result.injectFull()).isEmpty();
        assertThat(result.catalog()).hasSize(1);
        assertThat(result.catalog().get(0)).contains("技能 ID 1").contains("月报规范");
        verify(repository, never()).incrementHit(1L, 1L);
    }

    @Test
    void catalogCarriesDescriptionForProgressiveDisclosure() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "监控版本检查", "检查各服务版本是否符合规范",
                        "监控版本|版本检查", "必须检查构建用户，不超过 3 句话")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("帮我看看代码规范", 1L, 1L);

        assertThat(result.catalog()).hasSize(1);
        // 清单只含 ID+名称+描述（渐进披露：不泄露全文）
        String entry = result.catalog().get(0);
        assertThat(entry).contains("技能 ID 1").contains("监控版本检查")
                .contains("检查各服务版本是否符合规范");
        assertThat(entry).doesNotContain("构建用户");
    }

    @Test
    void capsInjectedSkillsAtTwo_restGoToCatalog() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "s1", "报告", "内容1"),
                skill(2L, "team", "s2", "报告", "内容2"),
                skill(3L, "team", "s3", "报告", "内容3")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("写一份报告", 1L, 1L);

        assertThat(result.injectFull()).hasSize(2);
        // 第 3 个命中技能进清单
        assertThat(result.catalog()).hasSize(1);
        assertThat(result.catalog().get(0)).contains("技能 ID 3");
        verify(repository).incrementHit(1L, 1L);
        verify(repository).incrementHit(1L, 2L);
    }

    @Test
    void truncatesLongContent() {
        SkillRepository repository = mock(SkillRepository.class);
        String longContent = "x".repeat(2000);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "长规范", "报告", longContent)
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("写一份报告", 1L, 1L);

        assertThat(result.injectFull().get(0).length()).isLessThan(1600);
    }

    @Test
    void emptyApplyToNeverMatches_goesToCatalog() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "无名", "", "内容")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("写一份报告", 1L, 1L);

        assertThat(result.injectFull()).isEmpty();
        assertThat(result.catalog()).hasSize(1);
        verify(repository, never()).incrementHit(1L, 1L);
    }

    @Test
    void recordLoadIncrementsHitCount() {
        SkillRepository repository = mock(SkillRepository.class);
        SkillMatcher matcher = new SkillMatcher(repository);

        matcher.recordLoad(1L, 5L);

        verify(repository).incrementHit(1L, 5L);
    }

    @Test
    void semanticRanksCatalogAndMarksRelatedSkills() {
        // P3 语义精排：embedding 可用时，清单按相似度降序 + 语义相关标记
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "监控版本检查", "查询监控版本并总结", "版本|buildinfo", "内容A"),
                skill(2L, "team", "离职交接规范", "离职流程指引", "离职|交接", "内容B"),
                skill(3L, "team", "月度经营报告", "经营分析月报", "经营|月报", "内容C")
        ));
        // mock embedding：question 与技能3（月度经营报告）方向一致（高相似），技能2（离职交接）低
        com.devmind.ai.AiModelGateway gateway = mock(com.devmind.ai.AiModelGateway.class);
        when(gateway.embed(anyList())).thenReturn(List.of(
                List.of(1.0, 0.0),           // question 向量
                List.of(0.0, 1.0),           // 技能2（离职交接，正交 → 0）
                List.of(0.8, 0.6)            // 技能3（月度经营，部分相似 → cos≈0.8）
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        matcher.setModelGateway(gateway);
        SkillMatcher.MatchResult result = matcher.match("帮我查一下监控版本", 1L, 1L);

        // 技能1 关键词"版本"命中注入全文；技能2/3 进清单
        assertThat(result.injectFull()).hasSize(1);
        assertThat(result.injectFull().get(0)).contains("监控版本检查");
        // 清单按相似度降序：技能3(0.8) 在 技能2(0) 前，且技能3 标记语义相关
        assertThat(result.catalog()).hasSize(2);
        assertThat(result.catalog().get(0)).contains("月度经营报告").contains("语义相关");
        assertThat(result.catalog().get(1)).contains("离职交接规范").doesNotContain("语义相关");
        verify(gateway).embed(anyList());
    }

    @Test
    void semanticRankingFallsBackToKeywordOnlyWhenEmbeddingFails() {
        // embedding 抛异常 → 降级为原顺序 + 无标记，不影响注入
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "监控版本检查", "d", "版本|buildinfo", "内容A"),
                skill(2L, "team", "月度经营报告", "d", "经营|月报", "内容C")
        ));
        com.devmind.ai.AiModelGateway gateway = mock(com.devmind.ai.AiModelGateway.class);
        when(gateway.embed(anyList())).thenThrow(new IllegalStateException("embedding 服务不可用"));

        SkillMatcher matcher = new SkillMatcher(repository);
        matcher.setModelGateway(gateway);
        SkillMatcher.MatchResult result = matcher.match("帮我查一下监控版本", 1L, 1L);

        // 关键词命中技能1 注入全文
        assertThat(result.injectFull()).hasSize(1);
        assertThat(result.injectFull().get(0)).contains("监控版本检查");
        // 技能2 进清单，无语义标记（降级）
        assertThat(result.catalog()).hasSize(1);
        assertThat(result.catalog().get(0)).contains("月度经营报告").doesNotContain("语义相关");
    }

    @Test
    void semanticRankingSkipsWhenNoGateway() {
        // 无 gateway（默认）→ 纯关键词匹配，清单原顺序无标记
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "监控版本检查", "d", "版本|buildinfo", "内容A"),
                skill(2L, "team", "月度经营报告", "d", "经营|月报", "内容C")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        SkillMatcher.MatchResult result = matcher.match("帮我查一下监控版本", 1L, 1L);

        assertThat(result.injectFull()).hasSize(1);
        assertThat(result.catalog()).hasSize(1);
        assertThat(result.catalog().get(0)).doesNotContain("语义相关");
    }
}
