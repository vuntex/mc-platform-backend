package com.mcplatform.protocol.economy;

import java.util.Objects;
import java.util.UUID;

/**
 * Pub/Sub event published whenever a player's balance changes — the shared contract between
 * backend and plugin. Pure data: no framework and no core-domain dependency, so {@code eventType}
 * is a {@code String} (values mirror the domain's economy event types). Wire format lives in
 * {@link BalanceChangedEventCodec}, carried inside a
 * {@link com.mcplatform.protocol.core.MessageEnvelope}.
 */
public record BalanceChangedEvent(
        UUID playerUuid,
        String currencyCode,
        String eventType,          // CREDITED|DEBITED|SET|TRANSFER_OUT|TRANSFER_IN
        long amount,               // always positive; direction implied by eventType
        long balance,              // balance after this event
        long version,              // sequence_no of the originating economy_event
        UUID transactionId,
        String source,             // e.g. WEB, PLUGIN:shop, SYSTEM:mobkill
        UUID correlationId,        // links the two legs of a transfer; null otherwise
        long timestampEpochMilli) {

    public BalanceChangedEvent {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(source, "source");
        // correlationId is nullable.
    }
}
