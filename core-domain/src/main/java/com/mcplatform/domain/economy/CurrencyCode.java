package com.mcplatform.domain.economy;

import java.util.Objects;

/** Identifier of a currency, e.g. {@code COINS}. */
public record CurrencyCode(String value) {

    public CurrencyCode {
        Objects.requireNonNull(value, "currency code must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("currency code must not be blank");
        }
    }

    public static CurrencyCode of(String value) {
        return new CurrencyCode(value);
    }
}
