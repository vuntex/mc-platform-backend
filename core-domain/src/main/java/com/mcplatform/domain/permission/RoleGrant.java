package com.mcplatform.domain.permission;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A rank grant: player → role, with audit/lifecycle fields. A player may hold several active grants at
 * once, each with its own {@code expiresAt} (FR-014). At most one active grant per (player, role) exists
 * — a re-grant is an upsert (FR-014a). {@code expiresAt == null} means permanent; {@code reason} is
 * optional. The {@code active} flag is the soft lifecycle state (set false on revoke/expire).
 */
public record RoleGrant(
        PlayerId player,
        RoleId role,
        UUID issuedBy,
        Instant issuedAt,
        Instant expiresAt,
        String reason,
        boolean active) {

    public RoleGrant {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(issuedBy, "issuedBy");
        Objects.requireNonNull(issuedAt, "issuedAt");
        // expiresAt and reason are nullable.
    }

    /**
     * Active at {@code now}: not soft-revoked and not past expiry. A grant whose {@code expiresAt} is at
     * or before {@code now} is inactive (FR-006).
     */
    public boolean isActive(Instant now) {
        return active && (expiresAt == null || now.isBefore(expiresAt));
    }
}
