package com.mcplatform.domain.economy;

import java.util.Objects;

/** The two legs of a transfer plus their shared correlation id. */
public record TransferEvents(PendingEconomyEvent out, PendingEconomyEvent in, TransferId correlationId) {

    public TransferEvents {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
