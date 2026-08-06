package com.devmind.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillMatcherTest {

    private Skill skill(Long id, String scope, String name, String applyTo, String content) {
        return new Skill(id, 1L, scope, name, "desc", applyTo, content,
                "manual", null, true, 0L, 1L, null);
    }

    private Skill skill(Long id, String scope, String name, String description,
                        String applyTo, String content) {
        return new Skill(id, 1L, scope, name, description, applyTo, content,
                "manual", null, true, 0L, 1L, null);
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
}
