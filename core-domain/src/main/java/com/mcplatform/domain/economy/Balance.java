package com.mcplatform.domain.economy;

import com.mcplatform.domain.player.PlayerId;
import java.util.Objects;

/**
 * The balance aggregate: a player's balance in one currency, plus the {@code version}
 * (= sequence_no of the last applied event) used for optimistic locking.
 *
 * <p>Balance is never mutated directly — every operation returns a {@link PendingEconomyEvent}
 * that the application layer appends to the event store. The domain enforces the invariants
 * (amount positive, no negative balance) here, before anything is persisted.
 */
public record Balance(PlayerId player, CurrencyCode currency, Money amount, long version) {

    public Balance {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("balance must not be negative");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    /** Starting point for a player/currency that has no events yet. */
    public static Balance initial(PlayerId player, CurrencyCode currency) {
        return new Balance(player, currency, Money.of(0), 0);
    }

    public PendingEconomyEvent credit(Money amount, TransactionId tx, String source) {
        requirePositive(amount);
        return event(EconomyEventType.CREDITED, amount, this.amount.plus(amount), tx, source, null);
    }

    public PendingEconomyEvent debit(Money amount, TransactionId tx, String source) {
        requirePositive(amount);
        if (amount.units() > this.amount.units()) {
            throw new InsufficientFundsException(player, currency, this.amount, amount);
        }
        return event(EconomyEventType.DEBITED, amount, this.amount.minus(amount), tx, source, null);
    }

    /** Admin override: set the balance to an absolute value (PROGRESS.md section 7). */
    public PendingEconomyEvent set(Money target, TransactionId tx, String source) {
        if (target.isNegative()) {
            throw new IllegalArgumentException("target balance must not be negative");
        }
        return event(EconomyEventType.SET, target, target, tx, source, null);
    }

    /** Sending leg of a transfer: subtracts, requires sufficient funds. Tagged with the correlation id. */
    public PendingEconomyEvent transferOut(Money amount, TransferId correlationId, String source) {
        requirePositive(amount);
        if (amount.units() > this.amount.units()) {
            throw new InsufficientFundsException(player, currency, this.amount, amount);
        }
        return event(EconomyEventType.TRANSFER_OUT, amount, this.amount.minus(amount),
                correlationId.outboundTransactionId(), source, correlationId);
    }

    /** Receiving leg of a transfer: adds. Tagged with the same correlation id as the sending leg. */
    public PendingEconomyEvent transferIn(Money amount, TransferId correlationId, String source) {
        requirePositive(amount);
        return event(EconomyEventType.TRANSFER_IN, amount, this.amount.plus(amount),
                correlationId.inboundTransactionId(), source, correlationId);
    }

    private PendingEconomyEvent event(EconomyEventType type, Money amount, Money after,
            TransactionId tx, String source, TransferId correlationId) {
        return new PendingEconomyEvent(player, currency, type, amount, after, tx, source, correlationId);
    }

    private static void requirePositive(Money amount) {
        if (amount.units() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount.units());
        }
    }
}
