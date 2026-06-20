package com.mcplatform.api.rest.support;

import com.mcplatform.application.player.SessionJoin;
import com.mcplatform.protocol.session.SessionJoinResponse;

/**
 * Maps the player/session domain result to the shared dependency-free protocol DTO. Reuses
 * {@link EconomyMapper#balanceResponse} so the per-currency balance shape stays identical.
 */
public final class SessionMapper {

    private SessionMapper() {}

    public static SessionJoinResponse sessionJoinResponse(SessionJoin join) {
        return new SessionJoinResponse(
                join.player().value(),
                join.name(),
                join.created(),
                join.balances().stream().map(EconomyMapper::balanceResponse).toList());
    }
}
