package com.mcplatform.application.economy;

import com.mcplatform.domain.economy.TransactionId;

/**
 * Thrown when a transaction-detail lookup finds no event for the given {@code transactionId} (spec 007,
 * US3). Mapped to HTTP 404 {@code economy_transaction_not_found} by {@code EconomyExceptionHandler}.
 */
public final class EconomyTransactionNotFoundException extends RuntimeException {

    public EconomyTransactionNotFoundException(TransactionId transactionId) {
        super("no economy transaction for id: " + transactionId.value());
    }
}
