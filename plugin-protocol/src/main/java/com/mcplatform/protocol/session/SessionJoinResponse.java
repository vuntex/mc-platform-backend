package com.mcplatform.protocol.session;

import com.mcplatform.protocol.economy.BalanceResponse;
import java.util.List;
import java.util.UUID;

/**
 * Response for a session join: the player, whether this join created them, and their current balances
 * (reusing {@link BalanceResponse} per currency). Pure data (JDK only); the backend builds it from the
 * domain join result.
 */
public record SessionJoinResponse(UUID player, String name, boolean created, List<BalanceResponse> balances) {
}
