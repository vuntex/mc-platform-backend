package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.ROLE_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.application.permission.port.RoleAuditPort;
import com.mcplatform.domain.permission.RoleId;
import java.time.Instant;
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
 * Integration test for the append-only {@code role_audit} writer (V13): role master-data + role-permission
 * actions land with the correct columns; {@code permission} is set only for the permission actions.
 */
@Testcontainers
class JooqRoleAuditRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqRoleAuditRepository audit;

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        audit = new JooqRoleAuditRepository(dsl);
    }

    @Test
    void recordsRoleMasterDataActionWithoutPermission() {
        UUID actor = UUID.randomUUID();
        audit.record(RoleAuditPort.Action.ROLE_CREATE, RoleId.of(42), "Premium", null, actor, Instant.now());

        var row = dsl.selectFrom(ROLE_AUDIT)
                .where(ROLE_AUDIT.ROLE_ID.eq(42L))
                .and(ROLE_AUDIT.ACTION.eq("ROLE_CREATE"))
                .fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.get(ROLE_AUDIT.ROLE_NAME)).isEqualTo("Premium");
        assertThat(row.get(ROLE_AUDIT.PERMISSION)).isNull();
        assertThat(row.get(ROLE_AUDIT.ACTOR)).isEqualTo(actor);
        assertThat(row.get(ROLE_AUDIT.AT)).isNotNull();
    }

    @Test
    void recordsRolePermissionActionWithPermission() {
        UUID actor = UUID.randomUUID();
        audit.record(RoleAuditPort.Action.ROLE_PERMISSION_ADD, RoleId.of(43), "Mod", "report.view", actor,
                Instant.now());

        var row = dsl.selectFrom(ROLE_AUDIT)
                .where(ROLE_AUDIT.ROLE_ID.eq(43L))
                .and(ROLE_AUDIT.ACTION.eq("ROLE_PERMISSION_ADD"))
                .fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.get(ROLE_AUDIT.PERMISSION)).isEqualTo("report.view");
        assertThat(row.get(ROLE_AUDIT.ACTOR)).isEqualTo(actor);
    }
}
