package com.mcplatform.protocol.economy;

import java.util.UUID;

/**
 * One entry of a player's economy history — a single recorded event from the append-only event store,
 * as the shared REST contract between backend and plugin/web. Pure data (JDK only); JSON
 * (de)serialization happens in the backend/plugin, never here. Field names are the wire contract.
 *
 * <p>{@code amount} is always positive; the direction is encoded by {@code eventType}
 * (CREDITED|DEBITED|SET|TRANSFER_OUT|TRANSFER_IN). {@code correlationId} is set only for the two legs
 * of a transfer (shared between them) and is {@code null} otherwise. {@code sequenceNo} is the global
 * ordering and doubles as the keyset-pagination cursor.
 */
public record EconomyEventEntry(
        long sequenceNo,
        String currencyCode,
        String eventType,
        long amount,
        long balanceAfter,
        UUID transactionId,
        String source,
        UUID correlationId,
        long timestampEpochMilli) {
}
