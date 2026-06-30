package com.mcplatform.api.rest.support;

import com.mcplatform.application.economy.EconomyHistoryEntry;
import com.mcplatform.application.economy.EconomyHistoryPage;
import com.mcplatform.application.economy.TransferOutcome;
import com.mcplatform.application.economy.port.ProjectedBalance;
import com.mcplatform.application.economy.port.TransactionDetail;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.economy.EconomyEventEntry;
import com.mcplatform.protocol.economy.EconomyHistoryResponse;
import com.mcplatform.protocol.economy.PlayerBalanceEntry;
import com.mcplatform.protocol.economy.PlayerBalancesResponse;
import com.mcplatform.protocol.economy.TransactionDetailResponse;
import com.mcplatform.protocol.economy.TransactionLegDto;
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

    /** Aggregate of all a player's balances with currency display metadata (spec 007, US1). */
    public static PlayerBalancesResponse playerBalances(UUID player, List<ProjectedBalance> balances) {
        List<PlayerBalanceEntry> entries = balances.stream()
                .map(b -> new PlayerBalanceEntry(
                        b.currency().value(),
                        b.displayName(),
                        b.symbol(),
                        b.decimalPlaces(),
                        b.balance().units()))
                .toList();
        return new PlayerBalancesResponse(player, entries);
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

    /** Server-wide history (spec 007, US2): no single player, so {@code player} is {@code null}; the
     * "who" of each event sits on the entry ({@code playerUuid}/{@code playerName}). */
    public static EconomyHistoryResponse serverHistoryResponse(EconomyHistoryPage page) {
        return historyResponse(null, page);
    }

    /** Optional free-form {@code source} filter from a query param: blank/absent → no filter. */
    public static Optional<String> sourceFilter(String source) {
        return (source == null || source.isBlank()) ? Optional.empty() : Optional.of(source);
    }

    /** Transaction detail (spec 007, US3): SINGLE → one leg, TRANSFER → both legs. */
    public static TransactionDetailResponse transactionDetail(TransactionDetail detail) {
        List<TransactionLegDto> legs = detail.legs().stream()
                .map(l -> new TransactionLegDto(
                        l.playerUuid(), l.playerName(), l.eventType().name(), l.balanceAfter().units()))
                .toList();
        return new TransactionDetailResponse(
                detail.transactionId().value(),
                detail.correlationId(),
                detail.kind().name(),
                detail.currency().value(),
                detail.displayName(),
                detail.symbol(),
                detail.decimalPlaces(),
                detail.amount().units(),
                detail.source(),
                detail.metadata(),
                detail.occurredAt().toEpochMilli(),
                legs);
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
                entry.occurredAt().toEpochMilli(),
                entry.playerUuid(),
                entry.playerName());
    }
}
