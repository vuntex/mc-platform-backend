package com.mcplatform.api.rest.support;

import com.mcplatform.application.economy.TransferOutcome;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.economy.TransferRequest;
import com.mcplatform.protocol.economy.TransferResponse;

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
}
