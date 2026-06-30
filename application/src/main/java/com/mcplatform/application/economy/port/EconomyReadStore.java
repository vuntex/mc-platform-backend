package com.mcplatform.application.economy.port;

import com.mcplatform.application.economy.EconomyHistoryPage;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.player.PlayerId;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for read-only economy projections (implemented by infra-persistence). These are pure
 * reads over the existing tables with no event/version/idempotency semantics — deliberately separated
 * from the security-critical {@link EconomyEventStore} (append/transfer) whose responsibility is the
 * write path. {@code findHistory} and {@code circulation} moved here 1:1 from {@link EconomyEventStore}
 * (pattern-leak fix, see specs/007-web-economy-read/plan.md); further web read models are added by the
 * later slice phases. Writes nothing.
 */
public interface EconomyReadStore {

    /**
     * Read-only audit query: a page of a player's economy history, newest-first (descending
     * sequence_no), via keyset pagination on sequence_no. Optionally filtered by {@code currency} and
     * {@code eventType}. {@code cursorBeforeSeqNo} is exclusive — only events with a strictly smaller
     * sequence_no are returned (pass {@code null} for the newest page). At most {@code limit} entries
     * are returned; the implementation determines {@link EconomyHistoryPage#nextCursor()} from whether
     * more rows exist.
     *
     * @param limit the (already-clamped) maximum number of entries to return; must be positive
     */
    EconomyHistoryPage findHistory(PlayerId player, Optional<CurrencyCode> currency,
            Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, int limit);

    /**
     * Read-only server-wide history (no player filter), newest-first via the same keyset pagination as
     * {@link #findHistory}, with an additional optional {@code source} filter (spec 007, US2). Each
     * entry carries its own {@code playerUuid}/{@code playerName}.
     *
     * @param limit the (already-clamped) maximum number of entries to return; must be positive
     */
    EconomyHistoryPage findServerHistory(Optional<CurrencyCode> currency,
            Optional<EconomyEventType> eventType, Optional<String> source, Long cursorBeforeSeqNo, int limit);

    /**
     * Read-only: total money in circulation per currency (SUM of all projected balances) plus the
     * account count, one entry per currency. Used for economy monitoring (circulation tracking + the
     * suspicious-amount threshold).
     */
    List<CirculationStats> circulation();

    /**
     * Read-only: all of a player's balances (one per currency) joined with currency display metadata
     * (spec 007, US1). An unknown player or one with no balance rows yields an empty list.
     */
    List<ProjectedBalance> playerBalances(PlayerId player);

    /**
     * Read-only: one transaction's detail by its business {@code transactionId} (spec 007, US3). A
     * single event yields one leg ({@link TransactionKind#SINGLE}); a transfer resolves its counter-leg
     * via the shared {@code correlation_id} and yields two legs ({@link TransactionKind#TRANSFER}). If
     * the counter-leg is missing, the present leg is returned as-is (still TRANSFER). Empty if the id is
     * unknown.
     */
    Optional<TransactionDetail> findTransaction(TransactionId transactionId);
}
