package com.mcplatform.domain.economy;

/**
 * Domain service for a transfer between two players in the same currency. Produces the two events
 * (TRANSFER_OUT from the sender, TRANSFER_IN to the receiver) that share one {@link TransferId}.
 * Validation (distinct players, matching currency, sufficient funds) happens here, before anything
 * is persisted.
 */
public final class Transfer {

    private Transfer() {}

    public static TransferEvents prepare(Balance from, Balance to, Money amount,
            TransferId correlationId, String source) {
        if (from.player().equals(to.player())) {
            throw new IllegalArgumentException("cannot transfer to the same player");
        }
        if (!from.currency().equals(to.currency())) {
            throw new IllegalArgumentException("transfer currency mismatch: "
                    + from.currency().value() + " vs " + to.currency().value());
        }
        PendingEconomyEvent out = from.transferOut(amount, correlationId, source); // may throw InsufficientFunds
        PendingEconomyEvent in = to.transferIn(amount, correlationId, source);
        return new TransferEvents(out, in, correlationId);
    }
}
