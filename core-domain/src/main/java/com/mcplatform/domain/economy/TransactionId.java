package com.mcplatform.domain.economy;

import com.mcplatform.domain.player.PlayerId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Idempotency key for an economy operation. A repeated operation carries the same id. */
public record TransactionId(UUID value) {

    public TransactionId {
        Objects.requireNonNull(value, "transaction id must not be null");
    }

    public static TransactionId of(UUID value) {
        return new TransactionId(value);
    }

    public static TransactionId random() {
        return new TransactionId(UUID.randomUUID());
    }

    /**
     * Deterministic key for a player's one-off initial balance in a currency. Derived purely from
     * (player, currency) so an accidental re-init hits the same {@code transaction_id} and is absorbed
     * by the idempotency constraint instead of crediting the starting amount twice.
     */
    public static TransactionId forInitialBalance(PlayerId player, CurrencyCode currency) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(currency, "currency");
        String seed = "initial-balance:" + player.value() + ":" + currency.value();
        return new TransactionId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
    }
}
