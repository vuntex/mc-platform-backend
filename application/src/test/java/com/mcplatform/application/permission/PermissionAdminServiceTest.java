package com.mcplatform.application.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.permission.PermissionFakes.FakeAudit;
import com.mcplatform.application.permission.PermissionFakes.FakeGrantRepository;
import com.mcplatform.application.permission.PermissionFakes.FakePublisher;
import com.mcplatform.application.permission.PermissionFakes.FakeResolver;
import com.mcplatform.application.permission.PermissionFakes.FakeRoleAudit;
import com.mcplatform.application.permission.PermissionFakes.FakeRoleInheritanceRepository;
import com.mcplatform.application.permission.PermissionFakes.FakeRoleRepository;
import com.mcplatform.application.permission.port.DefaultRoleProtectedException;
import com.mcplatform.application.permission.port.GrantAuditPort;
import com.mcplatform.application.permission.port.RoleAuditPort;
import com.mcplatform.application.permission.port.RoleNameConflictException;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.domain.permission.PermissionChangeType;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PermissionAdminServiceTest {

    private final UUID admin = UUID.randomUUID();
    private final UUID nobody = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-23T12:00:00Z"), ZoneOffset.UTC);

    private FakeResolver resolver;
    private FakeRoleRepository roles;
    private FakeGrantRepository grants;
    private FakeRoleInheritanceRepository inheritance;
    private FakeAudit audit;
    private FakeRoleAudit roleAudit;
    private FakePublisher publisher;
    private PermissionAdminService svc;
    private Role defaultRole;

    @BeforeEach
    void setUp() {
        resolver = new FakeResolver().grant(admin, "*");
        roles = new FakeRoleRepository();
        defaultRole = roles.seed(PermissionFakes.role(1, "DEFAULT", true, true));
        grants = new FakeGrantRepository();
        inheritance = new FakeRoleInheritanceRepository();
        audit = new FakeAudit();
        roleAudit = new FakeRoleAudit();
        publisher = new FakePublisher();
        svc = new PermissionAdminService(roles, grants, inheritance, audit, roleAudit, publisher, resolver, clock);
    }

    private Role draft(String name) {
        return PermissionFakes.role(0, name, false, true);
    }

    @Test
    void createRoleDeniedWithoutPermission() {
        assertThatThrownBy(() -> svc.createRole(draft("Supporter"), nobody))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void createRoleRejectsDuplicateNameCaseInsensitive() {
        svc.createRole(draft("Premium"), admin);
        assertThatThrownBy(() -> svc.createRole(draft("premium"), admin))
                .isInstanceOf(RoleNameConflictException.class);
    }

    @Test
    void defaultRoleCannotBeDeleted() {
        assertThatThrownBy(() -> svc.deleteRole(defaultRole.id(), admin))
                .isInstanceOf(DefaultRoleProtectedException.class);
    }

    @Test
    void defaultRoleCannotBeDeactivated() {
        Role deactivated = new Role(defaultRole.id(), "DEFAULT", "DEFAULT", null, null, null, null, null, null,
                0, false, false, true);
        assertThatThrownBy(() -> svc.updateRole(deactivated, admin))
                .isInstanceOf(DefaultRoleProtectedException.class);
    }

    @Test
    void grantRoleUpsertKeepsOneRowAndPermanentWins() {
        Role premium = svc.createRole(draft("Premium"), admin);
        PlayerId player = PlayerId.of(UUID.randomUUID());

        svc.grantRole(player, premium.id(), clock.instant().plusSeconds(3600), "temp", admin);
        svc.grantRole(player, premium.id(), null, "perm", admin); // re-grant permanent

        assertThat(grants.roleGrants).hasSize(1);
        assertThat(grants.activeRoleGrants(player, clock.instant())).singleElement()
                .satisfies(g -> assertThat(g.expiresAt()).isNull());
        assertThat(audit.count(GrantAuditPort.Action.GRANT)).isEqualTo(2);
    }

    @Test
    void multipleDistinctRanksCoexist() {
        Role premium = svc.createRole(draft("Premium"), admin);
        Role epic = svc.createRole(draft("Epic"), admin);
        PlayerId player = PlayerId.of(UUID.randomUUID());

        svc.grantRole(player, premium.id(), null, null, admin);
        svc.grantRole(player, epic.id(), clock.instant().plusSeconds(86400), null, admin);

        assertThat(grants.activeRoleGrants(player, clock.instant())).hasSize(2);
    }

    @Test
    void deleteRoleCascadesRevokeForEachHolderAndPublishes() {
        Role yt = svc.createRole(draft("YouTuber"), admin);
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());
        svc.grantRole(p1, yt.id(), null, null, admin);
        svc.grantRole(p2, yt.id(), null, null, admin);

        svc.deleteRole(yt.id(), admin);

        assertThat(roles.find(yt.id())).isEmpty();
        assertThat(audit.count(GrantAuditPort.Action.REVOKE)).isEqualTo(2);
        assertThat(publisher.countOfType(PermissionChangeType.GRANT_REVOKED)).isEqualTo(2);
    }

    @Test
    void roleConfigChangePublishesPerActiveHolder() {
        Role supporter = svc.createRole(draft("Supporter"), admin);
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());
        svc.grantRole(p1, supporter.id(), null, null, admin);
        svc.grantRole(p2, supporter.id(), null, null, admin);
        publisher.events.clear();

        svc.addRolePermission(supporter.id(), "report.view", admin);

        assertThat(publisher.countOfType(PermissionChangeType.ROLE_CONFIG_CHANGED)).isEqualTo(2);
        assertThat(roles.permissionsOf(supporter.id())).contains("report.view");
    }

    @Test
    void revokeRoleAuditsAndPublishesOnlyWhenActive() {
        Role premium = svc.createRole(draft("Premium"), admin);
        PlayerId player = PlayerId.of(UUID.randomUUID());
        svc.grantRole(player, premium.id(), null, null, admin);
        publisher.events.clear();

        svc.revokeRole(player, premium.id(), "manual", admin);
        svc.revokeRole(player, premium.id(), "again", admin); // no-op, already inactive

        assertThat(audit.count(GrantAuditPort.Action.REVOKE)).isEqualTo(1);
        assertThat(publisher.countOfType(PermissionChangeType.GRANT_REVOKED)).isEqualTo(1);
    }

    @Test
    void createRoleIsAuditedWithActingAdmin() {
        svc.createRole(draft("Premium"), admin);
        assertThat(roleAudit.count(RoleAuditPort.Action.ROLE_CREATE)).isEqualTo(1);
        assertThat(roleAudit.actors).containsOnly(admin);
    }

    @Test
    void roleMasterAndPermissionChangesAreAudited() {
        Role supporter = svc.createRole(draft("Supporter"), admin);
        svc.updateRole(new Role(supporter.id(), "Supporter", "Supporter+", null, null, null, null, null, null,
                5, false, true, false), admin);
        svc.addRolePermission(supporter.id(), "home.set", admin);
        svc.removeRolePermission(supporter.id(), "home.set", admin);
        svc.deleteRole(supporter.id(), admin);

        assertThat(roleAudit.count(RoleAuditPort.Action.ROLE_UPDATE)).isEqualTo(1);
        assertThat(roleAudit.count(RoleAuditPort.Action.ROLE_PERMISSION_ADD)).isEqualTo(1);
        assertThat(roleAudit.count(RoleAuditPort.Action.ROLE_PERMISSION_REMOVE)).isEqualTo(1);
        assertThat(roleAudit.count(RoleAuditPort.Action.ROLE_DELETE)).isEqualTo(1);
        assertThat(roleAudit.permissions).contains("home.set");
    }

    @Test
    void updateRoleDisplayPublishesToHolders() {
        Role premium = svc.createRole(draft("Premium"), admin);
        PlayerId holder = PlayerId.of(UUID.randomUUID());
        svc.grantRole(holder, premium.id(), null, null, admin);
        publisher.events.clear();

        // A pure display-name edit must push a live ROLE_CONFIG_CHANGED to the role's holders, so tab list,
        // chat format and the scoreboard rank line update without a rejoin (previously only `active` did).
        svc.updateRole(new Role(premium.id(), "Premium", "Premium+", null, null, null, null, null, null,
                0, false, true, false), admin);

        assertThat(publisher.countOfType(PermissionChangeType.ROLE_CONFIG_CHANGED)).isEqualTo(1);
    }

    @Test
    void defaultRoleCannotBeGranted() {
        PlayerId player = PlayerId.of(UUID.randomUUID());
        assertThatThrownBy(() -> svc.grantRole(player, defaultRole.id(), null, null, admin))
                .isInstanceOf(DefaultRoleProtectedException.class);
    }

    @Test
    void defaultRoleCannotBeRevoked() {
        PlayerId player = PlayerId.of(UUID.randomUUID());
        assertThatThrownBy(() -> svc.revokeRole(player, defaultRole.id(), null, admin))
                .isInstanceOf(DefaultRoleProtectedException.class);
    }

    @Test
    void grantRoleDeniedWithoutGrantPermission() {
        resolver.grant(nobody, PermissionAdminService.ROLE_CREATE); // has create but NOT grant.role
        Role premium = svc.createRole(draft("Premium"), admin);
        assertThatThrownBy(() -> svc.grantRole(PlayerId.of(UUID.randomUUID()), premium.id(), null, null, nobody))
                .isInstanceOf(PermissionDeniedException.class);
    }

    // --- inheritance (006) ------------------------------------------------

    @Test
    void addInheritanceDeniedWithoutInheritGate() {
        resolver.grant(nobody, PermissionAdminService.ROLE_EDIT); // role.edit but NOT role.edit.inherit
        Role premium = svc.createRole(draft("Premium"), admin);
        Role base = svc.createRole(draft("Base"), admin);
        assertThatThrownBy(() -> svc.addInheritance(premium.id(), base.id(), nobody))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void addInheritanceIsIdempotentAndPublishesOnceAndAudits() {
        Role premium = svc.createRole(draft("Premium"), admin);
        Role base = svc.createRole(draft("Base"), admin);
        PlayerId holder = PlayerId.of(UUID.randomUUID());
        svc.grantRole(holder, premium.id(), null, null, admin);
        publisher.events.clear();

        svc.addInheritance(premium.id(), base.id(), admin);
        svc.addInheritance(premium.id(), base.id(), admin); // re-add → idempotent no-op edge

        assertThat(inheritance.directParents(premium.id())).containsExactly(base.id());
        assertThat(roleAudit.count(RoleAuditPort.Action.ROLE_INHERITANCE_ADD)).isEqualTo(2);
        // each call publishes to the single holder of Premium
        assertThat(publisher.countOfType(PermissionChangeType.ROLE_CONFIG_CHANGED)).isEqualTo(2);
    }

    @Test
    void addInheritanceRejectsCycle() {
        Role a = svc.createRole(draft("A"), admin);
        Role b = svc.createRole(draft("B"), admin);
        svc.addInheritance(a.id(), b.id(), admin); // A inherits B
        assertThatThrownBy(() -> svc.addInheritance(b.id(), a.id(), admin)) // B inherits A → cycle
                .isInstanceOf(com.mcplatform.application.permission.port.RoleInheritanceCycleException.class);
        assertThat(inheritance.directParents(b.id())).isEmpty();
    }

    @Test
    void addInheritanceRejectsSelfReference() {
        Role a = svc.createRole(draft("A"), admin);
        assertThatThrownBy(() -> svc.addInheritance(a.id(), a.id(), admin))
                .isInstanceOf(com.mcplatform.application.permission.port.RoleInheritanceCycleException.class);
    }

    @Test
    void defaultRoleCannotInheritOthers() {
        Role base = svc.createRole(draft("Base"), admin);
        assertThatThrownBy(() -> svc.addInheritance(defaultRole.id(), base.id(), admin))
                .isInstanceOf(DefaultRoleProtectedException.class);
    }

    @Test
    void removeInheritanceIsIdempotentAndAuditsOnlyWhenPresent() {
        Role premium = svc.createRole(draft("Premium"), admin);
        Role base = svc.createRole(draft("Base"), admin);
        svc.addInheritance(premium.id(), base.id(), admin);

        svc.removeInheritance(premium.id(), base.id(), admin);
        svc.removeInheritance(premium.id(), base.id(), admin); // no-op

        assertThat(inheritance.directParents(premium.id())).isEmpty();
        assertThat(roleAudit.count(RoleAuditPort.Action.ROLE_INHERITANCE_REMOVE)).isEqualTo(1);
    }

    @Test
    void deleteRoleRejectedWhenInheritedByAnother() {
        Role premium = svc.createRole(draft("Premium"), admin);
        Role base = svc.createRole(draft("Base"), admin);
        svc.addInheritance(premium.id(), base.id(), admin);
        assertThatThrownBy(() -> svc.deleteRole(base.id(), admin))
                .isInstanceOf(com.mcplatform.application.permission.port.RoleInheritedException.class);
        assertThat(roles.find(base.id())).isPresent();
    }

    @Test
    void rolePermissionEditFansOutToTransitiveDependentsButNotUnrelatedHolders() {
        // Premium -> Base ; a Premium holder must be notified when Base's permissions change (FR-020a).
        Role base = svc.createRole(draft("Base"), admin);
        Role premium = svc.createRole(draft("Premium"), admin);
        Role unrelated = svc.createRole(draft("Unrelated"), admin);
        svc.addInheritance(premium.id(), base.id(), admin);

        PlayerId premiumHolder = PlayerId.of(UUID.randomUUID());
        PlayerId unrelatedHolder = PlayerId.of(UUID.randomUUID());
        svc.grantRole(premiumHolder, premium.id(), null, null, admin);
        svc.grantRole(unrelatedHolder, unrelated.id(), null, null, admin);
        publisher.events.clear();

        svc.addRolePermission(base.id(), "feature.a", admin);

        // Exactly the Premium holder is notified; the unrelated holder is not (FR-020/SC-005).
        assertThat(publisher.events).extracting(java.util.Map.Entry::getKey)
                .containsExactly(premiumHolder.value());
        assertThat(publisher.countOfType(PermissionChangeType.ROLE_CONFIG_CHANGED)).isEqualTo(1);
    }
}
