package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.PLAYER_ROLE_GRANT;
import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static com.mcplatform.persistence.jooq.Tables.ROLE_PERMISSION;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the full V9-backed permission resolver: seeded ADMIN/MODERATOR roles (consumer
 * compatibility), union over multiple active ranks, expiry via {@code now()}, wildcard matching,
 * deactivated-role exclusion (FR-007a) and the default-role fallback.
 */
@Testcontainers
class JooqPermissionResolverTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqPermissionResolver resolver;

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        resolver = new JooqPermissionResolver(dsl);
    }

    // --- helpers ----------------------------------------------------------

    private long roleId(String name) {
        return dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq(name)).fetchOne(ROLE.ID);
    }

    private long createRole(String name, boolean active, String... perms) {
        long id = dsl.insertInto(ROLE)
                .set(ROLE.NAME, name)
                .set(ROLE.DISPLAY_NAME, name)
                .set(ROLE.ACTIVE, active)
                .returning(ROLE.ID)
                .fetchOne()
                .get(ROLE.ID);
        for (String p : perms) {
            dsl.insertInto(ROLE_PERMISSION).set(ROLE_PERMISSION.ROLE_ID, id).set(ROLE_PERMISSION.PERMISSION, p).execute();
        }
        return id;
    }

    private UUID grantRole(long roleId, OffsetDateTime expiresAt) {
        UUID uuid = UUID.randomUUID();
        grantRoleTo(uuid, roleId, expiresAt);
        return uuid;
    }

    private void grantRoleTo(UUID uuid, long roleId, OffsetDateTime expiresAt) {
        dsl.insertInto(PLAYER_ROLE_GRANT)
                .set(PLAYER_ROLE_GRANT.UUID, uuid)
                .set(PLAYER_ROLE_GRANT.ROLE_ID, roleId)
                .set(PLAYER_ROLE_GRANT.ISSUED_BY, uuid)
                .set(PLAYER_ROLE_GRANT.EXPIRES_AT, expiresAt)
                .set(PLAYER_ROLE_GRANT.ACTIVE, true)
                .execute();
    }

    /** A staff member with the named seeded role (ADMIN/MODERATOR) — the consumer-facing setup. */
    private UUID member(String roleName) {
        return grantRole(roleId(roleName), null);
    }

    // --- consumer compatibility (SC-001) ----------------------------------

    @Test
    void adminWildcardGrantsEverything() {
        UUID admin = member("ADMIN");
        assertThat(resolver.hasPermission(admin, "punishment.cheating")).isTrue();
        assertThat(resolver.hasPermission(admin, "anything.at.all")).isTrue();
    }

    @Test
    void moderatorHasOnlyItsGrantedPermissions() {
        UUID mod = member("MODERATOR");
        assertThat(resolver.hasPermission(mod, "punishment.spam")).isTrue();
        assertThat(resolver.hasPermission(mod, "punishment.revoke")).isTrue();
        assertThat(resolver.hasPermission(mod, "report.view")).isTrue();
        assertThat(resolver.hasPermission(mod, "punishment.cheating")).isFalse();
    }

    // --- full model (T039) ------------------------------------------------

    @Test
    void unionOverMultipleActiveRanks() {
        long premium = createRole("Premium", true, "home.set", "home.tp");
        long epic = createRole("Epic", true, "fly");
        UUID uuid = UUID.randomUUID();
        grantRoleTo(uuid, premium, null);
        grantRoleTo(uuid, epic, null);

        assertThat(resolver.hasPermission(uuid, "home.set")).isTrue();
        assertThat(resolver.hasPermission(uuid, "fly")).isTrue();
        assertThat(resolver.hasPermission(uuid, "home.delete")).isFalse();
    }

    @Test
    void expiredRankDropsOut() {
        long temp = createRole("TempRank", true, "temp.perm");
        UUID uuid = grantRole(temp, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5));
        assertThat(resolver.hasPermission(uuid, "temp.perm")).isFalse();
    }

    @Test
    void wildcardPrefixMatchesBelowButNotBare() {
        long mod = createRole("Helper", true, "report.*");
        UUID uuid = grantRole(mod, null);
        assertThat(resolver.hasPermission(uuid, "report.view")).isTrue();
        assertThat(resolver.hasPermission(uuid, "report.x.y")).isTrue();
        assertThat(resolver.hasPermission(uuid, "reporting")).isFalse();
    }

    @Test
    void deactivatedRoleContributesNothing() {
        long disabled = createRole("Disabled", false, "secret.perm");
        UUID uuid = grantRole(disabled, null);
        assertThat(resolver.hasPermission(uuid, "secret.perm")).isFalse();
    }

    @Test
    void defaultRoleFallbackForPlayerWithoutGrant() {
        dsl.insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ROLE_ID, roleId("DEFAULT"))
                .set(ROLE_PERMISSION.PERMISSION, "lobby.join")
                .onConflictDoNothing()
                .execute();
        assertThat(resolver.hasPermission(UUID.randomUUID(), "lobby.join")).isTrue();
    }

    @Test
    void unknownMemberHasNothingBeyondDefault() {
        assertThat(resolver.hasPermission(UUID.randomUUID(), "punishment.spam")).isFalse();
    }

    @Test
    void directPermissionGrantIsHonoured() {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(com.mcplatform.persistence.jooq.Tables.PLAYER_PERMISSION_GRANT)
                .set(com.mcplatform.persistence.jooq.Tables.PLAYER_PERMISSION_GRANT.UUID, uuid)
                .set(com.mcplatform.persistence.jooq.Tables.PLAYER_PERMISSION_GRANT.PERMISSION, "kit.vip")
                .set(com.mcplatform.persistence.jooq.Tables.PLAYER_PERMISSION_GRANT.ISSUED_BY, uuid)
                .set(com.mcplatform.persistence.jooq.Tables.PLAYER_PERMISSION_GRANT.ACTIVE, true)
                .execute();
        assertThat(resolver.hasPermission(uuid, "kit.vip")).isTrue();
    }

    // --- inheritance (006) ------------------------------------------------

    private void inherit(long child, long parent) {
        dsl.insertInto(com.mcplatform.persistence.jooq.Tables.ROLE_INHERITANCE)
                .set(com.mcplatform.persistence.jooq.Tables.ROLE_INHERITANCE.ROLE_ID, child)
                .set(com.mcplatform.persistence.jooq.Tables.ROLE_INHERITANCE.INHERITED_ROLE_ID, parent)
                .onConflictDoNothing()
                .execute();
    }

    @Test
    void inheritedPermissionsResolveTransitively() {
        long a = createRole("ChainA", true);                 // no own perms
        long b = createRole("ChainB", true, "b.perm");
        long c = createRole("ChainC", true, "c.perm");
        inherit(a, b);
        inherit(b, c);
        UUID uuid = grantRole(a, null);

        assertThat(resolver.hasPermission(uuid, "b.perm")).isTrue();
        assertThat(resolver.hasPermission(uuid, "c.perm")).isTrue(); // transitive A->B->C
        assertThat(resolver.hasPermission(uuid, "x.perm")).isFalse();
    }

    @Test
    void inheritedWildcardMatches() { // FR-005
        long parent = createRole("WildParent", true, "report.*");
        long child = createRole("WildChild", true);
        inherit(child, parent);
        UUID uuid = grantRole(child, null);
        assertThat(resolver.hasPermission(uuid, "report.view")).isTrue();
        assertThat(resolver.hasPermission(uuid, "reporting")).isFalse();
    }

    @Test
    void inheritsFromDeactivatedParentStillContributesPermissions() { // FR-016
        long parent = createRole("DisabledParent", false, "ghost.perm"); // active = false
        long child = createRole("ActiveChild", true);
        inherit(child, parent);
        UUID uuid = grantRole(child, null);
        // The parent's active flag is not inherited; its permissions still flow through the edge.
        assertThat(resolver.hasPermission(uuid, "ghost.perm")).isTrue();
    }

    @Test
    void defaultPermissionsReachARealRoleOnlyViaExplicitInheritance() { // FR-011 / CL-1
        dsl.insertInto(ROLE_PERMISSION).set(ROLE_PERMISSION.ROLE_ID, roleId("DEFAULT"))
                .set(ROLE_PERMISSION.PERMISSION, "lobby.join").onConflictDoNothing().execute();
        long premium = createRole("PremiumX", true, "premium.fly");
        UUID withoutEdge = grantRole(premium, null);
        assertThat(resolver.hasPermission(withoutEdge, "lobby.join")).isFalse(); // no Default base (the trap)

        inherit(premium, roleId("DEFAULT"));
        UUID withEdge = grantRole(premium, null);
        assertThat(resolver.hasPermission(withEdge, "lobby.join")).isTrue();     // now via inheritance
    }

    @Test
    void resolutionTerminatesOnAResidualCycleInTheData() { // FR-010a defensive net
        long a = createRole("CycA", true, "a.perm");
        long b = createRole("CycB", true, "b.perm");
        inherit(a, b);
        inherit(b, a); // residual cycle (the write-path 409 would normally prevent this)
        UUID uuid = grantRole(a, null);
        assertThat(resolver.hasPermission(uuid, "a.perm")).isTrue();
        assertThat(resolver.hasPermission(uuid, "b.perm")).isTrue(); // terminates, unions both
    }

    @Test
    void emptyInheritanceGraphResolvesExactlyAsBeforeInheritance() { // FR-008 / SC-002 regression anchor
        long role = createRole("FlatRole", true, "flat.perm");
        UUID uuid = grantRole(role, null);
        assertThat(resolver.hasPermission(uuid, "flat.perm")).isTrue();
        assertThat(resolver.hasPermission(uuid, "other.perm")).isFalse();
    }
}
