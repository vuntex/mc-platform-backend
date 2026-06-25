package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.RoleId;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit of role configuration changes — role master data (create/update/delete) and
 * role-permission edits (FR-025a). Analogous to {@code config_audit} and the player-centric
 * {@link GrantAuditPort}, but for role-scoped events that have no single affected player. No FK to the
 * {@code role} table, so the trail survives a deleted role.
 */
public interface RoleAuditPort {

    enum Action { ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE, ROLE_PERMISSION_ADD, ROLE_PERMISSION_REMOVE }

    /**
     * One audit row. {@code roleName} is a snapshot (useful after deletion); {@code permission} is set
     * only for {@code ROLE_PERMISSION_ADD/REMOVE}; {@code actor} is the staff UUID from the verified token.
     */
    void record(Action action, RoleId role, String roleName, String permission, UUID actor, Instant at);
}
