package com.mcplatform.protocol.economy;

/**
 * One currency balance of a player, carrying the currency display metadata so the UI can format the
 * amount without a per-currency lookup (spec 007, US1). {@code symbol} may be {@code null};
 * {@code decimalPlaces} tells the UI where the comma goes (money stays a BIGINT in smallest units).
 * Pure data (JDK only); field names are the wire contract.
 */
public record PlayerBalanceEntry(
        String currencyCode,
        String displayName,
        String symbol,
        int decimalPlaces,
        long balance) {
}
