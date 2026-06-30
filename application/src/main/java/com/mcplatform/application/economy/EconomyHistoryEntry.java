package com.mcplatform.application.economy;

import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.economy.TransactionId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One read-only entry of a player's economy history, read straight from the event store. Mirrors a row
 * of {@code economy_event}; {@code amount} is always positive with direction encoded by {@code type}.
 * {@code sequenceNo} is the global ordering and the keyset-pagination cursor. {@code correlationId} is
 * the transfer correlation id read from event metadata — {@code null} for non-transfer events.
 * {@code counterpartyUuid} is the other party of a transfer (the opposite leg's player) — {@code null}
 * otherwise. {@code playerUuid} is the event's own player and {@code playerName} its resolved display
 * name (server-wide history's "who" column; on player-filtered history both refer to the queried player).
 */
public record EconomyHistoryEntry(
        long sequenceNo,
        CurrencyCode currency,
        EconomyEventType type,
        Money amount,
        Money balanceAfter,
        TransactionId transactionId,
        String source,
        UUID correlationId,
        UUID counterpartyUuid,
        Instant occurredAt,
        UUID playerUuid,
        String playerName) {

    public EconomyHistoryEntry {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(playerUuid, "playerUuid");
        // correlationId/counterpartyUuid nullable; playerName may be null if the player row is absent.
    }
}
