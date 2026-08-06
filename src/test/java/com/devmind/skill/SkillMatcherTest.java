package com.devmind.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillMatcherTest {

    private Skill skill(Long id, String scope, String name, String applyTo, String content) {
        return new Skill(id, 1L, scope, name, "desc", applyTo, content,
                "manual", null, true, 1L, null);
    }

    @Test
    void matchesQuestionByApplyToKeyword() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "月报规范", "月报|经营分析|月度报告", "生成月报必须包含同比环比。")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        List<String> result = matcher.match("帮我写一份月度经营分析报告", 1L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("月报规范").contains("同比环比");
    }

    @Test
    void doesNotInjectWhenNoKeywordHit() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "月报规范", "月报|经营分析", "生成月报必须包含同比环比。")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        List<String> result = matcher.match("今天天气怎么样", 1L, 1L);

        assertThat(result).isEmpty();
    }

    @Test
    void capsInjectedSkillsAtTwo() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "s1", "报告", "内容1"),
                skill(2L, "team", "s2", "报告", "内容2"),
                skill(3L, "team", "s3", "报告", "内容3")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        List<String> result = matcher.match("写一份报告", 1L, 1L);

        assertThat(result).hasSize(2);
    }

    @Test
    void truncatesLongContent() {
        SkillRepository repository = mock(SkillRepository.class);
        String longContent = "x".repeat(2000);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "长规范", "报告", longContent)
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        List<String> result = matcher.match("写一份报告", 1L, 1L);

        assertThat(result.get(0).length()).isLessThan(1600);
    }

    @Test
    void emptyApplyToNeverMatches() {
        SkillRepository repository = mock(SkillRepository.class);
        when(repository.listEnabledForUser(1L, 1L)).thenReturn(List.of(
                skill(1L, "team", "无名", "", "内容")
        ));

        SkillMatcher matcher = new SkillMatcher(repository);
        List<String> result = matcher.match("写一份报告", 1L, 1L);

        assertThat(result).isEmpty();
    }
}
