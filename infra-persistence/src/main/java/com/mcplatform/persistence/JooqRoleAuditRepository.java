package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.ROLE_AUDIT;

import com.mcplatform.application.permission.port.RoleAuditPort;
import com.mcplatform.domain.permission.RoleId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * jOOQ adapter for the append-only {@code role_audit} trail (role master-data + role-permission changes),
 * analogous to {@link JooqGrantAuditRepository}. Separate from the role tables so history survives role
 * deletion.
 */
public final class JooqRoleAuditRepository implements RoleAuditPort {

    private final DSLContext dsl;

    public JooqRoleAuditRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void record(Action action, RoleId role, String roleName, String permission, UUID actor, Instant at) {
        dsl.insertInto(ROLE_AUDIT)
                .set(ROLE_AUDIT.ACTION, action.name())
                .set(ROLE_AUDIT.ROLE_ID, role.value())
                .set(ROLE_AUDIT.ROLE_NAME, roleName)
                .set(ROLE_AUDIT.PERMISSION, permission)
                .set(ROLE_AUDIT.ACTOR, actor)
                .set(ROLE_AUDIT.AT, at.atOffset(ZoneOffset.UTC))
                .execute();
    }
}
