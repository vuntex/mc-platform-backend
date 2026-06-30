package com.mcplatform.protocol.economy;

import java.util.List;
import java.util.UUID;

/**
 * Detail of one transaction looked up by its business {@code transactionId} (spec 007, US3), as the
 * shared REST contract for the web detail page. Pure data (JDK only); JSON (de)serialization lives in
 * the backend/web. {@code kind} is "SINGLE" or "TRANSFER"; a transfer carries both {@code legs} and a
 * {@code correlationId}. {@code metadata} is the raw JSONB text (unparsed string); {@code symbol} may
 * be null. Field names are the wire contract.
 */
public record TransactionDetailResponse(
        UUID transactionId,
        UUID correlationId,
        String kind,
        String currencyCode,
        String displayName,
        String symbol,
        int decimalPlaces,
        long amount,
        String source,
        String metadata,
        long timestampEpochMilli,
        List<TransactionLegDto> legs) {
}
