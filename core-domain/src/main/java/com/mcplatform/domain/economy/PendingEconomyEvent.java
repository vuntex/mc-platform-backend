package com.mcplatform.domain.economy;

import com.mcplatform.domain.player.PlayerId;
import java.util.Objects;

/**
 * An economy event computed by the domain but not yet persisted (no sequence_no / timestamp yet).
 * {@code amount} is always positive; {@code balanceAfter} is the resulting balance. The direction is
 * encoded by {@code type} (PROGRESS.md section 7).
 */
public record PendingEconomyEvent(
        PlayerId player,
        CurrencyCode currency,
        EconomyEventType type,
        Money amount,
        Money balanceAfter,
        TransactionId transactionId,
        String source,
        TransferId correlationId) {

    public PendingEconomyEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(source, "source");
        // correlationId is nullable: set only for the two legs of a transfer.
        if (amount.isNegative()) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (balanceAfter.isNegative()) {
            throw new IllegalArgumentException("balanceAfter must not be negative");
        }
    }
}
