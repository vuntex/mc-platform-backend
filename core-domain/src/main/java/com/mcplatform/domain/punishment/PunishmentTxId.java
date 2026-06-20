package com.mcplatform.domain.punishment;

import java.util.Objects;
import java.util.UUID;

/**
 * Idempotency key for a punishment action (issue or revoke). A repeated action carries the same id,
 * so re-delivery is absorbed by the {@code uq_punishment_transaction} constraint instead of writing a
 * second event — exactly like the economy {@code TransactionId}.
 *
 * <p>This is intentionally a punishment-local type rather than a reuse of the economy
 * {@code TransactionId}: punishments are built as a parallel sibling to economy (no cross-feature
 * coupling). Once a second event-sourced feature exists, unifying both into one shared idempotency
 * key is a justified follow-up refactor (rule of three).
 */
public record PunishmentTxId(UUID value) {

    public PunishmentTxId {
        Objects.requireNonNull(value, "transaction id must not be null");
    }

    public static PunishmentTxId of(UUID value) {
        return new PunishmentTxId(value);
    }

    public static PunishmentTxId random() {
        return new PunishmentTxId(UUID.randomUUID());
    }
}
