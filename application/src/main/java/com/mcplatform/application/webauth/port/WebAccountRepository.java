package com.mcplatform.application.webauth.port;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;

/**
 * Outbound port for the state-stored web account. Implemented by the jOOQ adapter. The atomic
 * {@link #redeem} carries the whole single-use redemption (token lookup + account write + token delete +
 * audit) in one transaction (spec FR-018).
 */
public interface WebAccountRepository {

    /** Whether a web account already exists for this identity. */
    boolean exists(PlayerId playerUuid);

    /**
     * Redeem a raw token in ONE transaction: look up the matching unexpired token (FOR UPDATE), apply the
     * account write for its purpose (LINK → create, RESET → overwrite password), delete the token row
     * (single-use) and append an audit entry. All-or-nothing.
     *
     * @throws TokenInvalidException     if no matching unexpired token exists (unknown/expired/used)
     * @throws WebAccountConflictException if the account state no longer fits the purpose (LINK but it now
     *                                     exists / RESET but it is now gone)
     */
    RedeemOutcome redeem(String rawToken, String passwordHash, Instant now);
}
