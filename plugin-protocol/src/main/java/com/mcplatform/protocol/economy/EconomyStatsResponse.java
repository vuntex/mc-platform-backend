package com.mcplatform.protocol.economy;

/**
 * Economy circulation snapshot for a currency: total money in circulation (sum of all balances) and the
 * number of accounts. The shared REST contract for {@code GET /api/economy/stats/{currency}}. Pure data
 * (JDK only); field names are the wire contract.
 */
public record EconomyStatsResponse(String currency, long totalCirculation, int accountCount) {
}
