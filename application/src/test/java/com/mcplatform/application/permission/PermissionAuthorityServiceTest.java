package com.mcplatform.application.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleInheritanceRepository;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Authority computation, guards and read helpers with in-memory fakes. */
class PermissionAuthorityServiceTest {

    private final FakeRoles roles = new FakeRoles();
    private final FakeGrants grants = new FakeGrants();
    private final FakeInheritance inheritance = new FakeInheritance();
    private final FakeResolver resolver = new FakeResolver();
    private final PermissionAuthorityService svc =
            new PermissionAuthorityService(roles, grants, inheritance, resolver,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    private static final RoleId DEFAULT = RoleId.of(1);
    private static final RoleId MOD = RoleId.of(2);
    private static final RoleId ADMIN = RoleId.of(3);
    private static final RoleId PREMIUM = RoleId.of(4);
    private static final RoleId BASE = RoleId.of(5);
    private static final RoleId COSMETIC = RoleId.of(6);

    PermissionAuthorityServiceTest() {
        roles.add(role(DEFAULT, "DEFAULT", 0, true));
        roles.add(role(MOD, "MOD", 50, false));
        roles.add(role(ADMIN, "ADMIN", 100, false));
        roles.add(role(PREMIUM, "PREMIUM", 50, false));
        roles.add(role(BASE, "BASE", 10, false));
        roles.add(role(COSMETIC, "COSMETIC", 5, false));
        inheritance.parents.put(PREMIUM, List.of(BASE));   // Premium(50) → Base(10)
        inheritance.parents.put(COSMETIC, List.of(ADMIN));  // Cosmetic(5) → Admin(100): inheritance raises authority
    }

    // --- authorityWeight ---------------------------------------------------

    @Test
    void authorityWeightIsMaxOverReachableRoles() {
        UUID admin = player(ADMIN);
        assertThat(svc.authorityWeight(admin)).isEqualTo(100);
    }

    @Test
    void authorityWeightFallsBackToDefaultWhenNoRoles() {
        assertThat(svc.authorityWeight(UUID.randomUUID())).isEqualTo(0);
    }

    @Test
    void authorityWeightIncludesInheritedRoleWeights() {
        assertThat(svc.authorityWeight(player(PREMIUM))).isEqualTo(50);  // max(Premium 50, Base 10)
        assertThat(svc.authorityWeight(player(COSMETIC))).isEqualTo(100); // Cosmetic 5 inherits Admin 100
    }

    @Test
    void topWeightAndIsTopTier() {
        assertThat(svc.topWeight()).isEqualTo(100);
        assertThat(svc.isTopTier(player(ADMIN))).isTrue();
        assertThat(svc.isTopTier(player(MOD))).isFalse();
    }

    // --- guards ------------------------------------------------------------

    @Test
    void nonTopCannotManageOwnOrHigherRole() {
        UUID mod = player(MOD);
        assertThatThrownBy(() -> svc.requireCanManageRole(mod, roles.get(MOD)))
                .isInstanceOf(InsufficientAuthorityException.class);
        assertThatThrownBy(() -> svc.requireCanManageRole(mod, roles.get(ADMIN)))
                .isInstanceOf(InsufficientAuthorityException.class);
        assertThatCode(() -> svc.requireCanManageRole(mod, roles.get(DEFAULT))).doesNotThrowAnyException();
    }

    @Test
    void topTierManagesOwnLevelButNotAboveMax() {
        UUID admin = player(ADMIN);
        assertThatCode(() -> svc.requireCanManageRole(admin, roles.get(ADMIN))).doesNotThrowAnyException();
        assertThatThrownBy(() -> svc.requireCanManageWeight(admin, 101))
                .isInstanceOf(InsufficientAuthorityException.class);
    }

    @Test
    void cannotManageEqualOrHigherTarget() {
        UUID mod = player(MOD);
        assertThatThrownBy(() -> svc.requireCanManageTarget(mod, PlayerId.of(player(ADMIN))))
                .isInstanceOf(InsufficientAuthorityException.class);
        assertThatCode(() -> svc.requireCanManageTarget(mod, PlayerId.of(UUID.randomUUID())))
                .doesNotThrowAnyException(); // default-authority target below mod
    }

    @Test
    void delegationRequiresHoldingThePermissionAndWildcardRequiresStar() {
        UUID noStar = player(MOD);
        resolver.held.put(noStar, Set.of("economy.read"));
        assertThatCode(() -> svc.requireCanDelegate(noStar, "economy.read")).doesNotThrowAnyException();
        assertThatThrownBy(() -> svc.requireCanDelegate(noStar, "economy.write"))
                .isInstanceOf(InsufficientAuthorityException.class);
        assertThatThrownBy(() -> svc.requireCanDelegate(noStar, "economy.*"))
                .isInstanceOf(InsufficientAuthorityException.class);
        assertThatThrownBy(() -> svc.requireCanDelegate(noStar, "*"))
                .isInstanceOf(InsufficientAuthorityException.class);

        UUID withStar = player(ADMIN);
        resolver.held.put(withStar, Set.of("*"));
        assertThatCode(() -> svc.requireCanDelegate(withStar, "economy.*")).doesNotThrowAnyException();
        assertThatCode(() -> svc.requireCanDelegate(withStar, "anything.here")).doesNotThrowAnyException();
    }

    // --- lockout -----------------------------------------------------------

    @Test
    void lastTopTierHolderCannotBeRemovedButNonLastCan() {
        UUID admin1 = player(ADMIN);
        grants.holders.put(ADMIN, new ArrayList<>(List.of(PlayerId.of(admin1))));

        assertThatThrownBy(() -> svc.requireNotLastTopTierOnRevoke(PlayerId.of(admin1), roles.get(ADMIN)))
                .isInstanceOf(LastTopTierException.class);
        assertThatThrownBy(() -> svc.requireNotLastTopTierOnDelete(roles.get(ADMIN)))
                .isInstanceOf(LastTopTierException.class);

        UUID admin2 = UUID.randomUUID();
        grants.holders.get(ADMIN).add(PlayerId.of(admin2));
        assertThatCode(() -> svc.requireNotLastTopTierOnRevoke(PlayerId.of(admin1), roles.get(ADMIN)))
                .doesNotThrowAnyException();
    }

    @Test
    void lockoutIgnoresNonTopRole() {
        assertThatCode(() -> svc.requireNotLastTopTierOnRevoke(PlayerId.of(UUID.randomUUID()), roles.get(MOD)))
                .doesNotThrowAnyException();
    }

    // --- read helpers ------------------------------------------------------

    @Test
    void visibleRolesFilteredByCeilingAndRoleVisibility() {
        UUID mod = player(MOD);
        List<Role> visible = svc.visibleRoles(mod);
        assertThat(visible).extracting(Role::name).contains("DEFAULT", "BASE", "COSMETIC");
        assertThat(visible).extracting(Role::name).doesNotContain("MOD", "PREMIUM", "ADMIN");
        assertThat(svc.canViewRole(mod, roles.get(ADMIN))).isFalse();
        assertThat(svc.canViewRole(mod, roles.get(DEFAULT))).isTrue();
    }

    @Test
    void canViewTargetRespectsCeiling() {
        assertThat(svc.canViewTarget(player(MOD), PlayerId.of(player(ADMIN)))).isFalse();
        assertThat(svc.canViewTarget(player(ADMIN), PlayerId.of(player(ADMIN)))).isTrue(); // top-tier ≤
    }

    @Test
    void canViewTargetAlwaysAllowsSelf() {
        UUID mod = player(MOD); // non-top, equal authority to itself
        assertThat(svc.canViewTarget(mod, PlayerId.of(mod))).isTrue();
    }

    // --- helpers / fakes ---------------------------------------------------

    private static Role role(RoleId id, String name, int weight, boolean isDefault) {
        return new Role(id, name, name, null, null, null, null, null, null, weight, false, true, isDefault);
    }

    /** Registers a fresh player holding {@code role} and returns its uuid. */
    private UUID player(RoleId role) {
        UUID uuid = UUID.randomUUID();
        grants.roleGrants.put(uuid, List.of(role));
        grants.holders.computeIfAbsent(role, k -> new ArrayList<>()).add(PlayerId.of(uuid));
        return uuid;
    }

    private static final class FakeRoles implements RoleRepository {
        final Map<RoleId, Role> byId = new HashMap<>();
        void add(Role r) { byId.put(r.id(), r); }
        Role get(RoleId id) { return byId.get(id); }
        @Override public Optional<Role> find(RoleId id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<Role> findAll() { return List.copyOf(byId.values()); }
        @Override public Role findDefault() {
            return byId.values().stream().filter(Role::isDefault).findFirst().orElseThrow();
        }
        @Override public Role create(Role role, UUID createdBy) { throw new UnsupportedOperationException(); }
        @Override public Role update(Role role) { throw new UnsupportedOperationException(); }
        @Override public void delete(RoleId id) { throw new UnsupportedOperationException(); }
        @Override public Optional<Role> findByNameIgnoreCase(String name) { throw new UnsupportedOperationException(); }
        @Override public List<Role> findActiveByIds(Collection<RoleId> ids) { throw new UnsupportedOperationException(); }
        @Override public List<String> permissionsOf(RoleId id) { throw new UnsupportedOperationException(); }
        @Override public void addPermission(RoleId id, String p, UUID by) { throw new UnsupportedOperationException(); }
        @Override public void removePermission(RoleId id, String p) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeGrants implements PlayerGrantRepository {
        final Map<UUID, List<RoleId>> roleGrants = new HashMap<>();
        final Map<RoleId, List<PlayerId>> holders = new HashMap<>();
        @Override public List<RoleGrant> activeRoleGrants(PlayerId player, Instant now) {
            return roleGrants.getOrDefault(player.value(), List.of()).stream()
                    .map(r -> new RoleGrant(player, r, player.value(), Instant.EPOCH, null, null, true))
                    .toList();
        }
        @Override public List<PlayerId> activeHoldersOf(RoleId role, Instant now) {
            return List.copyOf(holders.getOrDefault(role, List.of()));
        }
        @Override public void upsertRoleGrant(RoleGrant grant) { throw new UnsupportedOperationException(); }
        @Override public boolean revokeRoleGrant(PlayerId p, RoleId r) { throw new UnsupportedOperationException(); }
        @Override public void upsertPermissionGrant(PermissionGrant grant) { throw new UnsupportedOperationException(); }
        @Override public boolean revokePermissionGrant(PlayerId p, String perm) { throw new UnsupportedOperationException(); }
        @Override public List<PermissionGrant> activePermissionGrants(PlayerId p, Instant now) { throw new UnsupportedOperationException(); }
        @Override public List<com.mcplatform.application.permission.port.ExpiredGrant> findExpired(Instant now) { throw new UnsupportedOperationException(); }
        @Override public void deactivate(com.mcplatform.application.permission.port.ExpiredGrant grant) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeInheritance implements RoleInheritanceRepository {
        final Map<RoleId, List<RoleId>> parents = new HashMap<>();
        @Override public List<RoleId> directParents(RoleId child) { return parents.getOrDefault(child, List.of()); }
        @Override public void add(RoleId c, RoleId p, UUID a) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(RoleId c, RoleId p) { throw new UnsupportedOperationException(); }
        @Override public List<RoleId> directChildren(RoleId parent) { throw new UnsupportedOperationException(); }
        @Override public List<RoleId> dependents(RoleId role) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeResolver implements PermissionResolver {
        final Map<UUID, Set<String>> held = new HashMap<>();
        @Override public boolean hasPermission(UUID uuid, String permission) {
            Set<String> grants = held.getOrDefault(uuid, Set.of());
            if (grants.contains(permission) || grants.contains("*")) {
                return true;
            }
            return grants.stream().anyMatch(g -> g.endsWith(".*")
                    && permission.startsWith(g.substring(0, g.length() - 1)));
        }
    }
}
