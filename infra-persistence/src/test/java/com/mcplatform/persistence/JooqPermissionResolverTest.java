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
}
