package com.mcplatform.domain.economy;

import com.mcplatform.domain.player.PlayerId;

/** Raised when a DEBITED / TRANSFER_OUT would drive a balance negative. */
public final class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(PlayerId player, CurrencyCode currency, Money balance, Money requested) {
        super("insufficient funds for player " + player.value() + " [" + currency.value()
                + "]: balance=" + balance.units() + " requested=" + requested.units());
    }
}
