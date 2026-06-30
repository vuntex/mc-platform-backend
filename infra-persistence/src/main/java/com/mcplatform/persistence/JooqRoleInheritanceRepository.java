package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.ROLE_INHERITANCE;

import com.mcplatform.application.permission.port.RoleInheritanceRepository;
import com.mcplatform.domain.permission.RoleId;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * jOOQ adapter over the state-stored {@code role_inheritance} edge table. Direct-edge CRUD via the
 * generated DSL; the transitive {@link #dependents} (reverse closure) is a single recursive CTE. The
 * forward transitive closure used for resolution/cycle-checks lives in the domain ({@code RoleHierarchy})
 * driven by {@link #directParents}. No Spring.
 */
public final class JooqRoleInheritanceRepository implements RoleInheritanceRepository {

    private final DSLContext dsl;

    public JooqRoleInheritanceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(RoleId child, RoleId parent, UUID actor) {
        dsl.insertInto(ROLE_INHERITANCE)
                .set(ROLE_INHERITANCE.ROLE_ID, child.value())
                .set(ROLE_INHERITANCE.INHERITED_ROLE_ID, parent.value())
                .set(ROLE_INHERITANCE.CREATED_BY, actor)
                .onConflictDoNothing() // idempotent (FR-014)
                .execute();
    }

    @Override
    public boolean remove(RoleId child, RoleId parent) {
        return dsl.deleteFrom(ROLE_INHERITANCE)
                .where(ROLE_INHERITANCE.ROLE_ID.eq(child.value())
                        .and(ROLE_INHERITANCE.INHERITED_ROLE_ID.eq(parent.value())))
                .execute() > 0;
    }

    @Override
    public List<RoleId> directParents(RoleId child) {
        return dsl.select(ROLE_INHERITANCE.INHERITED_ROLE_ID)
                .from(ROLE_INHERITANCE)
                .where(ROLE_INHERITANCE.ROLE_ID.eq(child.value()))
                .fetch(r -> RoleId.of(r.value1()));
    }

    @Override
    public List<RoleId> directChildren(RoleId parent) {
        return dsl.select(ROLE_INHERITANCE.ROLE_ID)
                .from(ROLE_INHERITANCE)
                .where(ROLE_INHERITANCE.INHERITED_ROLE_ID.eq(parent.value()))
                .fetch(r -> RoleId.of(r.value1()));
    }

    // Reverse transitive closure: all roles that inherit `role` directly or indirectly. UNION (not ALL)
    // dedups → terminates even on a residual cycle (FR-010a) over the finite role set.
    private static final String DEPENDENTS_SQL = """
            WITH RECURSIVE deps AS (
                SELECT role_id FROM role_inheritance WHERE inherited_role_id = ?
                UNION
                SELECT ri.role_id
                FROM role_inheritance ri
                JOIN deps d ON ri.inherited_role_id = d.role_id
            )
            SELECT role_id FROM deps
            """;

    @Override
    public List<RoleId> dependents(RoleId role) {
        return dsl.resultQuery(DEPENDENTS_SQL, role.value())
                .fetch(r -> RoleId.of(r.get(0, Long.class)));
    }
}
