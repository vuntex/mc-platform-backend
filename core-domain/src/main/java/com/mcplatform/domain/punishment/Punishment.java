package com.mcplatform.domain.punishment;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;

/**
 * The punishment aggregate — the current state folded from its events ({@code ISSUED}, optionally
 * {@code REVOKED}). {@code expiresAt == null} means permanent / not applicable (PERMABAN, WARN).
 * "Active" is a function of the revoke state, {@code expiresAt} and the wall clock — expiry needs no
 * event (PROGRESS.md / Prompt A).
 */
public record Punishment(
        PunishmentId id,
        PlayerId player,
        PunishmentType type,
        String reason,
        PlayerId issuedBy,
        Instant issuedAt,
        Instant expiresAt,   // null = permanent / not applicable
        PlayerId revokedBy,  // null until revoked
        Instant revokedAt,   // null until revoked
        long version) {      // sequence_no of the last applied punishment_event

    public Punishment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(issuedBy, "issuedBy");
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public PunishmentCategory category() {
        return type.category();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Active = issued, not revoked, and (permanent or not yet expired) at {@code now}. */
    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now");
        return !isRevoked() && (expiresAt == null || expiresAt.isAfter(now));
    }
}
