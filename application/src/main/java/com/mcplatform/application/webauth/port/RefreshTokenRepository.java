package com.mcplatform.application.webauth.port;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;

/**
 * Outbound port for the state-stored, rotating refresh token. Implemented by the jOOQ adapter. Callers
 * pass RAW tokens (mirroring the bridge's {@code LinkTokenRepository}/{@code WebAccountRepository}); the
 * adapter hashes them (SHA-256) and stores only the hash — the application never touches the hash
 * function. The {@link #rotate} primitive carries the whole rotation/replay decision in ONE transaction
 * (research R6).
 */
public interface RefreshTokenRepository {

    /** Persist a freshly login-issued refresh token (no predecessor) and append a {@code LOGIN} audit row. */
    void store(String rawToken, PlayerId player, Instant createdAt, Instant expiresAt);

    /**
     * Atomically rotate the presented token (one transaction, {@code SELECT … FOR UPDATE}):
     * active → mark consumed + insert {@code newRawToken} (rotated_from = presented) + audit
     * {@code TOKEN_ROTATED} → {@link RotateResult.Rotated}; already-consumed → delete ALL of the player's
     * tokens + audit {@code TOKEN_REUSE_DETECTED} → {@link RotateResult.Replay}; unknown/expired → no change
     * → {@link RotateResult.Invalid}.
     */
    RotateResult rotate(String presentedRawToken, String newRawToken, Instant now, Instant newExpiresAt);

    /**
     * Logout: delete the presented token if present and append a {@code LOGOUT} audit row. Idempotent.
     *
     * @return true iff a row was deleted
     */
    boolean deleteByRawToken(String rawToken);

    /** Delete all refresh tokens of a player (used by the bridge password-reset path, D4). Returns count. */
    int deleteAllForPlayer(PlayerId player);

    /** Hygiene: delete expired token rows. Returns count. */
    int purgeExpired(Instant now);
}
