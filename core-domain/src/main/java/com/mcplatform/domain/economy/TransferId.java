package com.mcplatform.domain.economy;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Correlation id for a transfer. A transfer is two events sharing this id (stored in metadata).
 * The per-leg {@link TransactionId}s are derived deterministically from it, so a replayed transfer
 * resolves to the same idempotency keys.
 */
public record TransferId(UUID value) {

    public TransferId {
        Objects.requireNonNull(value, "transfer id must not be null");
    }

    public static TransferId of(UUID value) {
        return new TransferId(value);
    }

    public static TransferId random() {
        return new TransferId(UUID.randomUUID());
    }

    public TransactionId outboundTransactionId() {
        return derive("OUT");
    }

    public TransactionId inboundTransactionId() {
        return derive("IN");
    }

    private TransactionId derive(String leg) {
        byte[] seed = (value.toString() + ':' + leg).getBytes(StandardCharsets.UTF_8);
        return TransactionId.of(UUID.nameUUIDFromBytes(seed));
    }
}
