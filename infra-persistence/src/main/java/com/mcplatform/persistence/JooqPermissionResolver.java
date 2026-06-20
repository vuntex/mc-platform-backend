package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.TEAM_ROLE_MEMBER;
import static com.mcplatform.persistence.jooq.Tables.TEAM_ROLE_PERMISSION;

import com.mcplatform.application.security.PermissionResolver;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * First {@link PermissionResolver} implementation: backed by the {@code team_role_member} /
 * {@code team_role_permission} tables (uuid → role, role → permissions). A {@code '*'} permission row
 * grants everything (admin role).
 *
 * <p>Chosen over stuffing role maps into {@code server_config}: a uuid→role→permission relation is a
 * natural, indexable many-to-many that two tiny tables express directly, whereas {@code server_config}
 * is a flat key→scalar store where this relation would be unqueryable JSONB. A later LuckPerms-backed
 * resolver replaces ONLY this class — everything depends on the {@link PermissionResolver} port.
 */
public final class JooqPermissionResolver implements PermissionResolver {

    private static final String WILDCARD = "*";

    private final DSLContext dsl;

    public JooqPermissionResolver(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean hasPermission(UUID staffUuid, String permission) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(TEAM_ROLE_MEMBER)
                        .join(TEAM_ROLE_PERMISSION).on(TEAM_ROLE_PERMISSION.ROLE.eq(TEAM_ROLE_MEMBER.ROLE))
                        .where(TEAM_ROLE_MEMBER.UUID.eq(staffUuid))
                        .and(TEAM_ROLE_PERMISSION.PERMISSION.eq(permission)
                                .or(TEAM_ROLE_PERMISSION.PERMISSION.eq(WILDCARD))));
    }
}
