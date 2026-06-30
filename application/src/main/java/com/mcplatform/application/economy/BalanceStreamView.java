package com.mcplatform.application.economy;

import java.util.UUID;

/**
 * Application-neutral view of a live balance change for the web SSE stream (spec 007, US4). Mirrors the
 * fields of the existing pub/sub balance event (no new wire codec); its field names ARE the SSE frame
 * JSON contract. The web resolves player names client-side from {@code playerUuid} (FR-020).
 */
public record BalanceStreamView(
        UUID playerUuid,
        String currencyCode,
        String eventType,
        long amount,
        long balance,
        long version,
        UUID transactionId,
        String source,
        UUID correlationId,
        long timestampEpochMilli) {
}
