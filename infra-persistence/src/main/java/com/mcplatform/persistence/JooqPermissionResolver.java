package com.mcplatform.persistence;

import com.mcplatform.application.security.PermissionResolver;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Full {@link PermissionResolver} implementation over the V9 permission model. Replaces the minimal
 * team-role lookup: the effective permission set is the UNION of (a) permissions of the player's active
 * rank grants on ACTIVE roles, (b) the default role's permissions when the player holds no such grant
 * (implicit fallback, FR-005), and (c) the player's active direct permission grants — matched against
 * the query with wildcard semantics ({@code *}, {@code feature.*}), no negations (FR-002/003/004).
 *
 * <p><b>Port signature unchanged.</b> Correctness lives in this SQL: activity is filtered against the DB
 * {@code now()} (so the answer is right even between expiry-sweep ticks) and against {@code role.active}
 * (FR-006/FR-007a) — the resolver stays clock-free. The pure rule is mirrored by
 * {@code com.mcplatform.domain.permission.PermissionMatcher} (tested in lockstep).
 */
public final class JooqPermissionResolver implements PermissionResolver {

    // One round trip. active_roles = the player's active grants on active roles; candidate = union of
    // their role permissions + default-role permissions (only when no active role) + active direct grants.
    // Match: exact, global '*', or prefix wildcard 'feature.*' via starts_with (no LIKE wildcard pitfalls).
    private static final String SQL = """
            WITH active_roles AS (
                SELECT g.role_id
                FROM player_role_grant g
                JOIN role r ON r.id = g.role_id AND r.active
                WHERE g.uuid = ? AND g.active AND (g.expires_at IS NULL OR g.expires_at > now())
            ),
            candidate AS (
                SELECT rp.permission
                FROM role_permission rp
                JOIN active_roles ar ON ar.role_id = rp.role_id
                UNION
                SELECT rp.permission
                FROM role_permission rp
                JOIN role r ON r.id = rp.role_id
                WHERE r.is_default AND NOT EXISTS (SELECT 1 FROM active_roles)
                UNION
                SELECT permission
                FROM player_permission_grant
                WHERE uuid = ? AND active AND (expires_at IS NULL OR expires_at > now())
            )
            SELECT EXISTS (
                SELECT 1 FROM candidate
                WHERE permission = ?
                   OR permission = '*'
                   OR (permission LIKE '%.*'
                       AND starts_with(?, left(permission, length(permission) - 1)))
            )
            """;

    private final DSLContext dsl;

    public JooqPermissionResolver(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean hasPermission(UUID staffUuid, String permission) {
        Boolean allowed = dsl.resultQuery(SQL, staffUuid, staffUuid, permission, permission)
                .fetchOne(0, Boolean.class);
        return Boolean.TRUE.equals(allowed);
    }
}
