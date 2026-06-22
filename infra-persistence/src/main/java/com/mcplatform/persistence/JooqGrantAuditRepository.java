package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.GRANT_AUDIT;

import com.mcplatform.application.permission.port.GrantAuditPort;
import java.time.ZoneOffset;
import org.jooq.DSLContext;

/**
 * jOOQ adapter for the append-only {@code grant_audit} trail (GRANT/REVOKE/EXPIRE), analogous to the
 * {@code config_audit} writer. Separate from the grant tables so the history survives role deletion.
 */
public final class JooqGrantAuditRepository implements GrantAuditPort {

    private final DSLContext dsl;

    public JooqGrantAuditRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void record(Entry e) {
        dsl.insertInto(GRANT_AUDIT)
                .set(GRANT_AUDIT.ACTION, e.action().name())
                .set(GRANT_AUDIT.GRANT_TYPE, e.type().name())
                .set(GRANT_AUDIT.PLAYER_UUID, e.player().value())
                .set(GRANT_AUDIT.ROLE_ID, e.role() == null ? null : e.role().value())
                .set(GRANT_AUDIT.PERMISSION, e.permission())
                .set(GRANT_AUDIT.ACTOR_UUID, e.actor())
                .set(GRANT_AUDIT.REASON, e.reason())
                .set(GRANT_AUDIT.AT, e.at().atOffset(ZoneOffset.UTC))
                .execute();
    }
}
