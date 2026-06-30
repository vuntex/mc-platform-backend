package com.mcplatform.application.economy;

import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.application.economy.port.TransactionDetail;
import com.mcplatform.domain.economy.TransactionId;

/**
 * Read-only use case (spec 007, US3): one transaction's detail by its business {@code transactionId}.
 * Single event → one leg; transfer → both legs (counter-leg resolved in the store). An unknown id is an
 * {@link EconomyTransactionNotFoundException} (→ 404). No mutation, no events.
 */
public final class TransactionDetailQuery {

    private final EconomyReadStore store;

    public TransactionDetailQuery(EconomyReadStore store) {
        this.store = store;
    }

    public TransactionDetail detail(TransactionId transactionId) {
        return store.findTransaction(transactionId)
                .orElseThrow(() -> new EconomyTransactionNotFoundException(transactionId));
    }
}
