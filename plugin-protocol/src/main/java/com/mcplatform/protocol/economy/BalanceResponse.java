package com.mcplatform.protocol.economy;

import java.util.UUID;

/**
 * Response body for a player's balance in one currency — the shared REST contract between backend and
 * plugin/web. Pure data (JDK only); JSON (de)serialization happens in the backend/plugin, never here.
 * Field names are the wire contract.
 */
public record BalanceResponse(UUID player, String currency, long balance, long version) {
}
