package com.mcplatform.domain.webauth;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;

/**
 * A short-lived, single-use trust carrier from the game into the web. Bound to one identity and one
 * {@link TokenPurpose}, with an expiry. The raw token value itself is the secret (≥128 bit, spec FR-024)
 * and is never modelled here — it is delivered to the player and stored only as a hash (research R3).
 */
public record LinkToken(PlayerId playerUuid, TokenPurpose purpose, Instant expiresAt, Instant createdAt) {

    /** Whether this token is no longer valid at {@code now} (expiry is the security boundary, FR-014). */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
