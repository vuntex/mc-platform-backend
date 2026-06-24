package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.List;

/**
 * Outbound port for player grants (rank + direct permission), state-stored with a soft {@code active}
 * flag. At most one row per (player, role) and per (player, permission) — re-granting is an upsert
 * (FR-014a). Implemented by the jOOQ adapter.
 */
public interface PlayerGrantRepository {

    /** Upserts a rank grant: max one active row per (player, role); permanent overrides temporary (FR-014a). */
    void upsertRoleGrant(RoleGrant grant);

    /** Soft-revokes a player's rank grant. Returns true iff a previously-active grant was deactivated. */
    boolean revokeRoleGrant(PlayerId player, RoleId role);

    /** Upserts a direct permission grant: max one active row per (player, permission). */
    void upsertPermissionGrant(PermissionGrant grant);

    /** Soft-revokes a player's direct permission grant. Returns true iff a previously-active grant was deactivated. */
    boolean revokePermissionGrant(PlayerId player, String permission);

    /** The player's active (not soft-revoked, not past {@code expiresAt}) rank grants at {@code now}. */
    List<RoleGrant> activeRoleGrants(PlayerId player, Instant now);

    /** The player's active direct permission grants at {@code now}. */
    List<PermissionGrant> activePermissionGrants(PlayerId player, Instant now);

    /** Players holding an active rank grant for {@code role} at {@code now} — fan-out + cascade-delete (FR-012a/R7). */
    List<PlayerId> activeHoldersOf(RoleId role, Instant now);

    /** Grants whose {@code expiresAt <= now} that are still flagged active — the sweep work-list (FR-020). */
    List<ExpiredGrant> findExpired(Instant now);

    /** Deactivates a single expired grant (sets {@code active=false}); no-op if already inactive. */
    void deactivate(ExpiredGrant grant);
}
