package com.mcplatform.application.economy.port;

import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.economy.TransactionId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only detail of one transaction looked up by its business {@code transactionId} (spec 007, US3).
 * {@code kind} is SINGLE (one leg) or TRANSFER (two legs — sender + receiver; or one leg if the
 * counter-leg is missing, which the read path surfaces as-is, never invents). {@code correlationId} is
 * set for transfers; {@code metadata} is the raw JSONB text (unparsed); {@code symbol} may be null.
 */
public record TransactionDetail(
        TransactionId transactionId,
        UUID correlationId,
        TransactionKind kind,
        CurrencyCode currency,
        String displayName,
        String symbol,
        int decimalPlaces,
        Money amount,
        String source,
        String metadata,
        Instant occurredAt,
        List<TransactionLeg> legs) {
}
