package com.mcplatform.protocol.economy;

import java.util.UUID;

/**
 * One entry of a player's economy history — a single recorded event from the append-only event store,
 * as the shared REST contract between backend and plugin/web. Pure data (JDK only); JSON
 * (de)serialization happens in the backend/plugin, never here. Field names are the wire contract.
 *
 * <p>{@code amount} is always positive; the direction is encoded by {@code eventType}
 * (CREDITED|DEBITED|SET|TRANSFER_OUT|TRANSFER_IN). {@code correlationId} is set only for the two legs
 * of a transfer (shared between them) and is {@code null} otherwise. {@code counterpartyUuid} is the
 * OTHER party of a transfer (the receiver on a TRANSFER_OUT, the sender on a TRANSFER_IN), derived from
 * the opposite leg; {@code null} for non-transfer events. {@code sequenceNo} is the global ordering and
 * doubles as the keyset-pagination cursor.
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
        UUID counterpartyUuid,
        long timestampEpochMilli) {
}
