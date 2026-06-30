package com.mcplatform.application.economy.port;

import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;

/**
 * Read-only projection of one of a player's balances joined with its currency's display metadata
 * (spec 007, US1). Pure read model — no invariants, no events. {@code symbol} may be {@code null}
 * (the currency column is nullable); {@code decimalPlaces} only tells the UI where to put the comma.
 */
public record ProjectedBalance(
        CurrencyCode currency,
        String displayName,
        String symbol,
        int decimalPlaces,
        Money balance) {
}
