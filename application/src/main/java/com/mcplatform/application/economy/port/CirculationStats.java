package com.mcplatform.application.economy.port;

import com.mcplatform.domain.economy.CurrencyCode;

/** Total money in circulation for one currency: the sum of all account balances and the account count. */
public record CirculationStats(CurrencyCode currency, long total, int accounts) {
}
