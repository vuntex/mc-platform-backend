package com.mcplatform.domain.webauth;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A persisted refresh token represented as DATA, not the raw secret. State-stored (Constitution
 * principle 6): a session datum, not a money/judgement aggregate. Framework-free and JWT-free — the
 * access token lives behind the TokenIssuer/TokenVerifier ports, not here.
 *
 * <p>{@code tokenHash} is the SHA-256 hex of the raw token (the row's identity, never plaintext).
 * {@code rotatedAt} marks consumption: {@code null} = active, set = already rotated. {@code rotatedFrom}
 * is the predecessor's hash — a lineage breadcrumb only; it does NOT scope invalidation (a theft signal
 * invalidates ALL of a player's tokens via {@code playerUuid}, per the 2026-06-24 clarification).
 */
public record RefreshToken(
        String tokenHash,
        PlayerId playerUuid,
        Instant createdAt,
        Instant expiresAt,
        Instant rotatedAt,
        String rotatedFrom) {

    public RefreshToken {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /** Whether this token's lifetime has elapsed at {@code now} (boundary-inclusive: expiry instant = expired). */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Whether this token was already rotated/consumed. */
    public boolean isConsumed() {
        return rotatedAt != null;
    }

    /** Usable for a refresh: neither consumed nor expired. */
    public boolean isActive(Instant now) {
        return !isConsumed() && !isExpired(now);
    }

    /** The predecessor hash, if this token was issued by rotating another. */
    public Optional<String> predecessor() {
        return Optional.ofNullable(rotatedFrom);
    }
}
