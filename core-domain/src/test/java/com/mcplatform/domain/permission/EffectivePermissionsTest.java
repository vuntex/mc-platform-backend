package com.mcplatform.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EffectivePermissionsTest {

    private static final RoleId PREMIUM = RoleId.of(1);
    private static final RoleId EPIC = RoleId.of(2);

    @Test
    void unionOverMultipleActiveRanks() {
        EffectivePermissions eff = EffectivePermissions.resolve(
                Map.of(PREMIUM, List.of("home.set", "home.tp"), EPIC, List.of("fly")),
                List.of(),
                List.of());

        assertThat(eff.allows("home.set")).isTrue();
        assertThat(eff.allows("home.tp")).isTrue();
        assertThat(eff.allows("fly")).isTrue();
        assertThat(eff.permissions()).containsExactlyInAnyOrder("home.set", "home.tp", "fly");
    }

    @Test
    void defaultRoleFallbackWhenNoActiveRank() {
        EffectivePermissions eff = EffectivePermissions.resolve(
                Map.of(),
                List.of("lobby.join"),
                List.of());

        assertThat(eff.allows("lobby.join")).isTrue();
    }

    @Test
    void defaultRoleIgnoredWhenAnyActiveRankPresent() {
        EffectivePermissions eff = EffectivePermissions.resolve(
                Map.of(PREMIUM, List.of("home.set")),
                List.of("default.only"),
                List.of());

        assertThat(eff.allows("home.set")).isTrue();
        assertThat(eff.allows("default.only")).isFalse();
    }

    @Test
    void directGrantsAreAddedAdditively() {
        EffectivePermissions eff = EffectivePermissions.resolve(
                Map.of(PREMIUM, List.of("home.set")),
                List.of(),
                List.of("kit.vip"));

        assertThat(eff.allows("kit.vip")).isTrue();
        assertThat(eff.allows("home.set")).isTrue();
    }

    @Test
    void purelyAdditiveNoImplicitExtras() {
        EffectivePermissions eff = EffectivePermissions.resolve(
                Map.of(PREMIUM, List.of("home.set")),
                List.of(),
                List.of());

        assertThat(eff.allows("home.delete")).isFalse();
    }

    @Test
    void wildcardFromDirectGrant() {
        EffectivePermissions eff = EffectivePermissions.resolve(
                Map.of(), Set.of(), List.of("feature.*"));

        assertThat(eff.allows("feature.x")).isTrue();
    }
}
