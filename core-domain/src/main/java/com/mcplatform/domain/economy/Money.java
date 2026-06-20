package com.mcplatform.domain.economy;

/**
 * Money as a whole number in the currency's smallest unit (like cents).
 * Always a {@code long} (maps to BIGINT) — never a float. The position of the
 * decimal point is purely a UI concern ({@code currency.decimal_places}).
 */
public record Money(long units) {

    public static Money of(long units) {
        return new Money(units);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(this.units, other.units));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(this.units, other.units));
    }

    public boolean isNegative() {
        return units < 0;
    }
}
