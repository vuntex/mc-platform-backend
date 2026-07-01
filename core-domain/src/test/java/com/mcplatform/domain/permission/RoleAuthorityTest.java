package com.mcplatform.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure authority-ceiling comparisons: non-top strict {@code <}, top-tier {@code ≤}, wildcard detection. */
class RoleAuthorityTest {

    @Test
    void nonTopManagesStrictlyBelowOwnLevel() {
        assertThat(RoleAuthority.canManageWeight(49, 50, false)).isTrue();
        assertThat(RoleAuthority.canManageWeight(50, 50, false)).as("own level not manageable").isFalse();
        assertThat(RoleAuthority.canManageWeight(51, 50, false)).isFalse();
    }

    @Test
    void topTierManagesOwnLevelButNotAbove() {
        assertThat(RoleAuthority.canManageWeight(100, 100, true)).as("own level ≤").isTrue();
        assertThat(RoleAuthority.canManageWeight(101, 100, true)).as("never above the ceiling").isFalse();
    }

    @Test
    void targetRuleMirrorsWeightRule() {
        assertThat(RoleAuthority.canManageTarget(49, 50, false)).isTrue();
        assertThat(RoleAuthority.canManageTarget(50, 50, false)).isFalse();
        assertThat(RoleAuthority.canManageTarget(100, 100, true)).isTrue();
    }

    @Test
    void wildcardDetection() {
        assertThat(RoleAuthority.isWildcard("*")).isTrue();
        assertThat(RoleAuthority.isWildcard("economy.*")).isTrue();
        assertThat(RoleAuthority.isWildcard("economy.read")).isFalse();
        assertThat(RoleAuthority.isWildcard(null)).isFalse();
    }
}
