package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit of grant lifecycle actions (GRANT / REVOKE / EXPIRE), analogous to
 * {@code config_audit} / {@code report_status_history} (FR-018). Separate from the current-state grant
 * tables: a deactivated/cascade-deleted grant still leaves its trail here.
 */
public interface GrantAuditPort {

    enum Action { GRANT, REVOKE, EXPIRE }

    void record(Entry entry);

    /**
     * One audit row. Exactly one of {@code role}/{@code permission} is set per {@code type}; {@code actor}
     * is the staff UUID or the configured SYSTEM sentinel for automatic EXPIRE entries (FR-016a);
     * {@code reason} is optional.
     */
    record Entry(Action action, GrantType type, PlayerId player, RoleId role, String permission,
            UUID actor, String reason, Instant at) {

        public static Entry role(Action action, PlayerId player, RoleId role, UUID actor, String reason,
                Instant at) {
            return new Entry(action, GrantType.ROLE, player, role, null, actor, reason, at);
        }

        public static Entry permission(Action action, PlayerId player, String permission, UUID actor,
                String reason, Instant at) {
            return new Entry(action, GrantType.PERMISSION, player, null, permission, actor, reason, at);
        }
    }
}
