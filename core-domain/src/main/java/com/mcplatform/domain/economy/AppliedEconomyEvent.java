package com.mcplatform.domain.economy;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;

/**
 * A persisted economy event: a {@link PendingEconomyEvent} enriched with the global ordering
 * ({@code version} = sequence_no) and the time it was recorded. This is what gets published as a
 * live-update event.
 */
public record AppliedEconomyEvent(
        PlayerId player,
        CurrencyCode currency,
        EconomyEventType type,
        Money amount,
        Money balanceAfter,
        long version,
        TransactionId transactionId,
        String source,
        TransferId correlationId,
        Instant occurredAt) {

    public AppliedEconomyEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(occurredAt, "occurredAt");
        // correlationId is nullable: set only for the two legs of a transfer.
    }
}
