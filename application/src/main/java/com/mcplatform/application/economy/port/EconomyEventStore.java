package com.mcplatform.application.economy.port;

import com.mcplatform.application.economy.EconomyHistoryPage;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.PendingEconomyEvent;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.domain.player.PlayerId;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port to the event-sourced economy store (implemented by infra-persistence).
 * The append/transfer are the security-critical operations: they must insert the event(s) and
 * update the projection(s) in ONE transaction with optimistic locking (PROGRESS.md sections 3 &amp; 6).
 */
public interface EconomyEventStore {

    /** Current projected balance, or {@link Balance#initial} if the player has no events yet. */
    Balance currentBalance(PlayerId player, CurrencyCode currency);

    /**
     * Ensure a zero-balance projection row exists for {@code (player, currency)}, creating it at
     * balance 0 / version 0 if absent. Writes no event — folding zero events is 0, so the row stays a
     * faithful projection. Used to materialise a consistent 0 row for a currency whose configured
     * default balance is 0 (no point writing a CREDITED 0). Idempotent.
     */
    void ensureZeroBalance(PlayerId player, CurrencyCode currency);

    /**
     * Append {@code event} and project it onto player_balance in a single transaction, guarded by
     * {@code expectedVersion} (the version the caller computed against).
     *
     * @throws ConcurrencyConflictException if the projection changed concurrently (version mismatch)
     */
    AppendResult append(PendingEconomyEvent event, long expectedVersion);

    /**
     * Atomic transfer: insert both legs (sharing the correlation id) and project both balances in
     * ONE transaction, each guarded by its expected version. Either both apply or neither does.
     *
     * @throws ConcurrencyConflictException if either projection changed concurrently
     */
    TransferResult transfer(PendingEconomyEvent out, long expectedFromVersion,
            PendingEconomyEvent in, long expectedToVersion);

    /** Look up a previously recorded operation by its transaction id (for idempotent replay). */
    Optional<AppendResult> findByTransactionId(TransactionId transactionId);

    /** Look up a previously recorded transfer by its correlation id (for idempotent replay). */
    Optional<TransferResult> findTransfer(TransferId correlationId);

    /**
     * Read-only audit query: a page of a player's economy history, newest-first (descending
     * sequence_no), via keyset pagination on sequence_no. Optionally filtered by {@code currency} and
     * {@code eventType}. {@code cursorBeforeSeqNo} is exclusive — only events with a strictly smaller
     * sequence_no are returned (pass {@code null} for the newest page). At most {@code limit} entries
     * are returned; the implementation determines {@link EconomyHistoryPage#nextCursor()} from whether
     * more rows exist. Writes nothing.
     *
     * @param limit the (already-clamped) maximum number of entries to return; must be positive
     */
    EconomyHistoryPage findHistory(PlayerId player, Optional<CurrencyCode> currency,
            Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, int limit);

    /**
     * Read-only: total money in circulation per currency (SUM of all projected balances) plus the
     * account count, one entry per currency. Used for economy monitoring (circulation tracking + the
     * suspicious-amount threshold). Writes nothing.
     */
    List<CirculationStats> circulation();
}
