package com.mcplatform.domain.permission;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A direct permission grant: player → single permission string (independent of any role), with the same
 * audit/lifecycle fields as {@link RoleGrant}. Wildcards are allowed in {@code permission} (FR-015).
 * At most one active grant per (player, permission) — a re-grant is an upsert. {@code expiresAt == null}
 * means permanent.
 */
public record PermissionGrant(
        PlayerId player,
        String permission,
        UUID issuedBy,
        Instant issuedAt,
        Instant expiresAt,
        String reason,
        boolean active) {

    public PermissionGrant {
        Objects.requireNonNull(player, "player");
        if (permission == null || permission.isBlank()) {
            throw new InvalidGrantException("permission must not be blank");
        }
        Objects.requireNonNull(issuedBy, "issuedBy");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    /** Active at {@code now}: not soft-revoked and not past expiry (FR-006). */
    public boolean isActive(Instant now) {
        return active && (expiresAt == null || now.isBefore(expiresAt));
    }
}
