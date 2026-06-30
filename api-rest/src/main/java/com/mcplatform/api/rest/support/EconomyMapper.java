package com.mcplatform.api.rest.support;

import com.mcplatform.application.economy.EconomyHistoryEntry;
import com.mcplatform.application.economy.EconomyHistoryPage;
import com.mcplatform.application.economy.TransferOutcome;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.economy.EconomyEventEntry;
import com.mcplatform.protocol.economy.EconomyHistoryResponse;
import com.mcplatform.protocol.economy.TransferRequest;
import com.mcplatform.protocol.economy.TransferResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps between the shared dependency-free protocol DTOs and the economy domain/application types. The
 * protocol records stay pure data (JDK only); all domain coupling for the economy endpoints lives
 * here. Behaviour mirrors the former DTO helper methods exactly.
 */
public final class EconomyMapper {

    private EconomyMapper() {}

    public static BalanceResponse balanceResponse(Balance balance) {
        return new BalanceResponse(
                balance.player().value(),
                balance.currency().value(),
                balance.amount().units(),
                balance.version());
    }

    public static TransferResponse transferResponse(TransferOutcome outcome) {
        return new TransferResponse(balanceResponse(outcome.from()), balanceResponse(outcome.to()));
    }

    /** Stable id from the request, or a fresh random one — keeps credit/debit/set idempotent on retry. */
    public static TransactionId transactionId(AmountRequest request) {
        return request.transactionId() != null
                ? TransactionId.of(request.transactionId())
                : TransactionId.random();
    }

    /** Stable correlation id from the request, or a fresh random one — keeps a transfer idempotent. */
    public static TransferId correlationId(TransferRequest request) {
        return request.correlationId() != null
                ? TransferId.of(request.correlationId())
                : TransferId.random();
    }

    /** Returns {@code source} when present, otherwise the endpoint-specific {@code fallback}. */
    public static String sourceOr(String source, String fallback) {
        return (source == null || source.isBlank()) ? fallback : source;
    }

    /** Optional currency filter from a query param: blank/absent → no filter. */
    public static Optional<CurrencyCode> currencyFilter(String currency) {
        return (currency == null || currency.isBlank()) ? Optional.empty() : Optional.of(CurrencyCode.of(currency));
    }

    /**
     * Optional event-type filter from a query param: blank/absent → no filter; an unknown value is an
     * {@link IllegalArgumentException} (mapped to 400 by {@code EconomyExceptionHandler}).
     */
    public static Optional<EconomyEventType> eventTypeFilter(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(EconomyEventType.valueOf(type));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown event type: " + type);
        }
    }

    public static EconomyHistoryResponse historyResponse(UUID player, EconomyHistoryPage page) {
        List<EconomyEventEntry> entries = page.entries().stream().map(EconomyMapper::historyEntry).toList();
        return new EconomyHistoryResponse(player, entries, page.nextCursor());
    }

    private static EconomyEventEntry historyEntry(EconomyHistoryEntry entry) {
        return new EconomyEventEntry(
                entry.sequenceNo(),
                entry.currency().value(),
                entry.type().name(),
                entry.amount().units(),
                entry.balanceAfter().units(),
                entry.transactionId().value(),
                entry.source(),
                entry.correlationId(),
                entry.counterpartyUuid(),
                entry.occurredAt().toEpochMilli());
    }
}
