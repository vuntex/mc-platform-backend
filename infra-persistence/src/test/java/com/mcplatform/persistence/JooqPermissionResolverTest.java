package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.TEAM_ROLE_MEMBER;
import static org.assertj.core.api.Assertions.assertThat;

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

/** Integration test for the team-role-backed permission resolver (seeded roles + runtime members). */
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

    private UUID member(String role) {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(TEAM_ROLE_MEMBER).set(TEAM_ROLE_MEMBER.UUID, uuid).set(TEAM_ROLE_MEMBER.ROLE, role).execute();
        return uuid;
    }

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
        assertThat(resolver.hasPermission(mod, "punishment.cheating")).isFalse();
    }

    @Test
    void unknownMemberHasNothing() {
        assertThat(resolver.hasPermission(UUID.randomUUID(), "punishment.spam")).isFalse();
    }
}
