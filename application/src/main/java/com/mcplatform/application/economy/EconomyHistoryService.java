package com.mcplatform.application.economy;

import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.player.PlayerId;
import java.util.Optional;

/**
 * Read-only use case for the economy audit trail: serves a keyset-paginated page of a player's history
 * straight from the event store. No business mutation, no cache, no events — Postgres is the source of
 * truth and this never writes.
 *
 * <p>The server owns the page size: {@code limit} defaults to {@value #DEFAULT_LIMIT} when absent and
 * is clamped to at most {@value #MAX_LIMIT}; an explicitly non-positive limit is rejected
 * ({@link IllegalArgumentException} → 400). The cursor is optional ({@code null} = newest page).
 */
public final class EconomyHistoryService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final EconomyReadStore store;

    public EconomyHistoryService(EconomyReadStore store) {
        this.store = store;
    }

    /**
     * @param limit requested page size; {@code null} → default. Non-positive is invalid; larger than
     *     the maximum is clamped down.
     */
    public EconomyHistoryPage history(PlayerId player, Optional<CurrencyCode> currency,
            Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, Integer limit) {
        return store.findHistory(player, currency, eventType, cursorBeforeSeqNo, clampLimit(limit));
    }

    /**
     * Server-owned page-size policy, shared by all economy history reads (player- and server-wide):
     * {@code null} → {@value #DEFAULT_LIMIT}; non-positive → {@link IllegalArgumentException} (→ 400);
     * larger than {@value #MAX_LIMIT} → clamped down. Single source so the two paths never drift.
     */
    public static int clampLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + requested);
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
