package com.mcplatform.application.economy.port;

import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;

/** A configured currency and its starting balance (read from {@code currency.default_balance}). */
public record CurrencyDefault(CurrencyCode code, Money defaultBalance) {
}
