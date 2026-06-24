package com.mcplatform.application.webauth.port;

import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.webauth.TokenPurpose;
import java.time.Instant;
import java.util.Optional;

/**
 * Outbound port for the short-lived web-auth tokens (state-stored). The raw token is hashed at rest by
 * the adapter (research R3); this port speaks raw token values.
 */
public interface LinkTokenRepository {

    /**
     * Issue a token for {@code (playerUuid, purpose)} in ONE transaction: delete any existing token of
     * the same purpose for this player, then insert the new one (at most one active token per
     * (uuid, purpose), spec FR-013).
     */
    void issue(String rawToken, PlayerId playerUuid, TokenPurpose purpose, Instant expiresAt, Instant now);

    /**
     * Creation time of the currently-live token for {@code (playerUuid, purpose)}, if any — the cooldown
     * anchor (spec FR-022).
     */
    Optional<Instant> lastCreatedAt(PlayerId playerUuid, TokenPurpose purpose);

    /** Delete all tokens that have expired at {@code now}. Hygiene only — not the security boundary. */
    int deleteExpired(Instant now);
}
