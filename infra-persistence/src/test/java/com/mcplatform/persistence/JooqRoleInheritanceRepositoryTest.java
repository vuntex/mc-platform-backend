package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.domain.permission.RoleId;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the {@code role_inheritance} jOOQ adapter (V15): idempotent add, remove,
 * direct parents/children, transitive dependents (reverse closure) and the ON DELETE RESTRICT net that
 * backs FR-015.
 */
@Testcontainers
class JooqRoleInheritanceRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqRoleInheritanceRepository repo;

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        repo = new JooqRoleInheritanceRepository(dsl);
    }

    private long createRole(String name) {
        return dsl.insertInto(ROLE).set(ROLE.NAME, name).set(ROLE.DISPLAY_NAME, name)
                .returning(ROLE.ID).fetchOne().get(ROLE.ID);
    }

    @Test
    void addIsIdempotentAndRemoveReportsPresence() {
        RoleId child = RoleId.of(createRole("IdemChild"));
        RoleId parent = RoleId.of(createRole("IdemParent"));
        repo.add(child, parent, UUID.randomUUID());
        repo.add(child, parent, UUID.randomUUID()); // no duplicate (PK + onConflictDoNothing)
        assertThat(repo.directParents(child)).containsExactly(parent);

        assertThat(repo.remove(child, parent)).isTrue();
        assertThat(repo.remove(child, parent)).isFalse(); // idempotent no-op
        assertThat(repo.directParents(child)).isEmpty();
    }

    @Test
    void directParentsAndChildren() {
        RoleId a = RoleId.of(createRole("DpA"));
        RoleId b = RoleId.of(createRole("DpB"));
        RoleId c = RoleId.of(createRole("DpC"));
        repo.add(a, b, null);
        repo.add(a, c, null);
        assertThat(repo.directParents(a)).containsExactlyInAnyOrder(b, c);
        assertThat(repo.directChildren(b)).containsExactly(a);
    }

    @Test
    void dependentsAreTheTransitiveReverseClosure() {
        // grandChild -> child -> base
        RoleId base = RoleId.of(createRole("DepBase"));
        RoleId child = RoleId.of(createRole("DepChild"));
        RoleId grandChild = RoleId.of(createRole("DepGrandChild"));
        repo.add(child, base, null);
        repo.add(grandChild, child, null);
        assertThat(repo.dependents(base)).containsExactlyInAnyOrder(child, grandChild);
        assertThat(repo.dependents(grandChild)).isEmpty();
    }

    @Test
    void deletingAnInheritedRoleIsBlockedByRestrict() { // FR-015 DB net
        RoleId base = RoleId.of(createRole("RestrictBase"));
        RoleId child = RoleId.of(createRole("RestrictChild"));
        repo.add(child, base, null);
        assertThatThrownBy(() -> dsl.deleteFrom(ROLE).where(ROLE.ID.eq(base.value())).execute())
                .isInstanceOf(DataAccessException.class); // ON DELETE RESTRICT on inherited_role_id
    }

    @Test
    void deletingChildCascadesItsEdges() {
        RoleId base = RoleId.of(createRole("CascBase"));
        RoleId child = RoleId.of(createRole("CascChild"));
        repo.add(child, base, null);
        dsl.deleteFrom(ROLE).where(ROLE.ID.eq(child.value())).execute(); // child delete cascades the edge
        assertThat(repo.directChildren(base)).isEmpty();
    }
}
