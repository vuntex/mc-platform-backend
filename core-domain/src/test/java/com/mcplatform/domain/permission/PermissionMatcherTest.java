package com.mcplatform.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionMatcherTest {

    @Test
    void globalWildcardMatchesAnything() {
        assertThat(PermissionMatcher.matches(Set.of("*"), "anything.at.all")).isTrue();
    }

    @Test
    void prefixWildcardMatchesBelowButNotBare() {
        Set<String> granted = Set.of("report.*");
        assertThat(PermissionMatcher.matches(granted, "report.view")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "report.x.y")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "report")).isFalse();
        assertThat(PermissionMatcher.matches(granted, "reporting")).isFalse();
    }

    @Test
    void exactMatchAndMiss() {
        Set<String> granted = Set.of("home.set");
        assertThat(PermissionMatcher.matches(granted, "home.set")).isTrue();
        assertThat(PermissionMatcher.matches(granted, "home.delete")).isFalse();
    }

    @Test
    void noNegationCanCancelAMatch() {
        // There is no deny syntax; "-report.view" is just an unrelated string that matches nothing.
        assertThat(PermissionMatcher.matches(List.of("report.*", "-report.view"), "report.view")).isTrue();
    }

    @Test
    void emptyGrantsMatchNothing() {
        assertThat(PermissionMatcher.matches(Set.of(), "anything")).isFalse();
    }
}
