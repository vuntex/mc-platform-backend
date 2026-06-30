package com.mcplatform.protocol.economy;

import java.util.List;
import java.util.UUID;

/**
 * Response body for a player's balances across ALL currencies in one call (spec 007, US1). Pure data
 * (JDK only); JSON (de)serialization happens in the backend/web, never here. An unknown player or a
 * player with no balance rows yields an empty {@code balances} list (not a 404). Field names are the
 * wire contract.
 */
public record PlayerBalancesResponse(UUID player, List<PlayerBalanceEntry> balances) {
}
