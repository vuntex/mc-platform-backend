package com.mcplatform.application.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.application.permission.PermissionFakes.FakeGrantRepository;
import com.mcplatform.application.permission.PermissionFakes.FakeRoleInheritanceRepository;
import com.mcplatform.application.permission.PermissionFakes.FakeRoleRepository;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Use-case tests for the transitive effective view + the default interplay (CL-1/FR-011): default
 * permissions reach a real role ONLY via an explicit inheritance edge; a player with no role still gets
 * the default fallback. Also checks the per-permission provenance (FR-022a).
 */
class PermissionQueryServiceInheritanceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);
    private FakeRoleRepository roles;
    private FakeGrantRepository grants;
    private FakeRoleInheritanceRepository inheritance;
    private PermissionQueryService query;
    private Role defaultRole;
    private Role premium;
    private Role base;

    @BeforeEach
    void setUp() {
        roles = new FakeRoleRepository();
        grants = new FakeGrantRepository();
        inheritance = new FakeRoleInheritanceRepository();
        query = new PermissionQueryService(roles, grants, inheritance, clock);

        defaultRole = roles.seed(PermissionFakes.role(1, "DEFAULT", true, true));
        roles.addPermission(defaultRole.id(), "lobby.join", null);
        premium = roles.seed(PermissionFakes.role(2, "Premium", false, true));
        roles.addPermission(premium.id(), "premium.fly", null);
        base = roles.seed(PermissionFakes.role(3, "Base", false, true));
        roles.addPermission(base.id(), "base.home", null);
    }

    private PlayerId grant(RoleId role) {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        grants.upsertRoleGrant(new RoleGrant(p, role, p.value(), clock.instant(), null, null, true));
        return p;
    }

    @Test
    void premiumInheritingDefaultGetsDefaultPermissions() {
        inheritance.add(premium.id(), defaultRole.id(), null);
        PlayerId player = grant(premium.id());

        var view = query.effectiveFor(player);
        assertThat(view.effectivePermissions()).contains("premium.fly", "lobby.join");
        // provenance: lobby.join inherited from DEFAULT (id 1), not own
        assertThat(view.sources()).anySatisfy(s -> {
            assertThat(s.permission()).isEqualTo("lobby.join");
            assertThat(s.own()).isFalse();
            assertThat(s.inheritedFromRoleIds()).contains(defaultRole.id().value());
        });
    }

    @Test
    void premiumNotInheritingDefaultHasNoDefaultBase() {
        // CL-1 consistency trap: runs through correctly, player has fewer perms than a default player.
        PlayerId player = grant(premium.id());
        var view = query.effectiveFor(player);
        assertThat(view.effectivePermissions()).contains("premium.fly");
        assertThat(view.effectivePermissions()).doesNotContain("lobby.join");
    }

    @Test
    void transitiveChainUnionsAllPermissions() {
        inheritance.add(premium.id(), base.id(), null); // Premium -> Base
        PlayerId player = grant(premium.id());
        var view = query.effectiveFor(player);
        assertThat(view.effectivePermissions()).contains("premium.fly", "base.home");
        assertThat(view.sources()).anySatisfy(s -> {
            assertThat(s.permission()).isEqualTo("base.home");
            assertThat(s.inheritedFromRoleIds()).contains(base.id().value());
        });
    }

    @Test
    void playerWithoutAnyRoleFallsBackToDefault() {
        PlayerId player = PlayerId.of(UUID.randomUUID());
        var view = query.effectiveFor(player);
        assertThat(view.effectivePermissions()).containsExactly("lobby.join");
        assertThat(view.sources()).anySatisfy(s -> {
            assertThat(s.permission()).isEqualTo("lobby.join");
            assertThat(s.inheritedFromRoleIds()).contains(defaultRole.id().value());
        });
    }
}
