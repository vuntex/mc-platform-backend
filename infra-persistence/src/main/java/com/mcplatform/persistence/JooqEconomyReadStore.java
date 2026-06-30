package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.CURRENCY;
import static com.mcplatform.persistence.jooq.Tables.ECONOMY_EVENT;
import static com.mcplatform.persistence.jooq.Tables.PLAYER;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_BALANCE;

import com.mcplatform.application.economy.EconomyHistoryEntry;
import com.mcplatform.application.economy.EconomyHistoryPage;
import com.mcplatform.application.economy.port.CirculationStats;
import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.application.economy.port.ProjectedBalance;
import com.mcplatform.application.economy.port.TransactionDetail;
import com.mcplatform.application.economy.port.TransactionKind;
import com.mcplatform.application.economy.port.TransactionLeg;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.player.PlayerId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * jOOQ adapter for read-only economy projections (PROGRESS.md sections 3 &amp; 6). Pure reads, no
 * transaction/locking — separated from {@link JooqEconomyRepository} (the event-sourced write path).
 * {@code findHistory} and {@code circulation} were moved here 1:1 from that repository.
 */
public final class JooqEconomyReadStore implements EconomyReadStore {

    private final DSLContext dsl;

    public JooqEconomyReadStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<CirculationStats> circulation() {
        return dsl.select(PLAYER_BALANCE.CURRENCY_CODE, DSL.sum(PLAYER_BALANCE.BALANCE), DSL.count())
                .from(PLAYER_BALANCE)
                .groupBy(PLAYER_BALANCE.CURRENCY_CODE)
                .fetch(r -> {
                    BigDecimal sum = r.value2();
                    return new CirculationStats(
                            CurrencyCode.of(r.value1()),
                            sum == null ? 0L : sum.longValue(),
                            r.value3());
                });
    }

    @Override
    public List<ProjectedBalance> playerBalances(PlayerId player) {
        return dsl.select(PLAYER_BALANCE.CURRENCY_CODE, CURRENCY.DISPLAY_NAME, CURRENCY.SYMBOL,
                        CURRENCY.DECIMAL_PLACES, PLAYER_BALANCE.BALANCE)
                .from(PLAYER_BALANCE)
                .join(CURRENCY).on(PLAYER_BALANCE.CURRENCY_CODE.eq(CURRENCY.CODE))
                .where(PLAYER_BALANCE.PLAYER_UUID.eq(player.value()))
                .orderBy(PLAYER_BALANCE.CURRENCY_CODE)
                .fetch(r -> new ProjectedBalance(
                        CurrencyCode.of(r.value1()),
                        r.value2(),
                        r.value3(),
                        r.value4() == null ? 0 : r.value4().intValue(),
                        Money.of(r.value5())));
    }

    @Override
    public Optional<TransactionDetail> findTransaction(TransactionId transactionId) {
        // correlation_id lives in the JSONB metadata; ->> yields it as text (null when absent).
        Field<String> correlationId =
                DSL.field("{0} ->> 'correlation_id'", String.class, ECONOMY_EVENT.METADATA);

        var primary = dsl.select(ECONOMY_EVENT.EVENT_TYPE, ECONOMY_EVENT.CURRENCY_CODE, CURRENCY.DISPLAY_NAME,
                        CURRENCY.SYMBOL, CURRENCY.DECIMAL_PLACES, ECONOMY_EVENT.AMOUNT, ECONOMY_EVENT.SOURCE,
                        ECONOMY_EVENT.METADATA, ECONOMY_EVENT.CREATED_AT, correlationId,
                        ECONOMY_EVENT.PLAYER_UUID, PLAYER.NAME, ECONOMY_EVENT.BALANCE_AFTER)
                .from(ECONOMY_EVENT)
                .join(CURRENCY).on(ECONOMY_EVENT.CURRENCY_CODE.eq(CURRENCY.CODE))
                .join(PLAYER).on(ECONOMY_EVENT.PLAYER_UUID.eq(PLAYER.UUID))
                .where(ECONOMY_EVENT.TRANSACTION_ID.eq(transactionId.value()))
                .fetchOne();
        if (primary == null) {
            return Optional.empty();
        }

        EconomyEventType primaryType = EconomyEventType.valueOf(primary.value1());
        boolean isTransfer = primaryType == EconomyEventType.TRANSFER_OUT
                || primaryType == EconomyEventType.TRANSFER_IN;
        String corr = primary.value10();

        List<TransactionLeg> legs;
        if (isTransfer && corr != null) {
            // Both legs share the correlation id; order OUT before IN ("TRANSFER_OUT" > "TRANSFER_IN").
            // A missing counter-leg simply yields one leg here — read paths surface, never invent.
            legs = dsl.select(ECONOMY_EVENT.PLAYER_UUID, PLAYER.NAME, ECONOMY_EVENT.EVENT_TYPE,
                            ECONOMY_EVENT.BALANCE_AFTER)
                    .from(ECONOMY_EVENT)
                    .join(PLAYER).on(ECONOMY_EVENT.PLAYER_UUID.eq(PLAYER.UUID))
                    .where(correlationId.eq(corr))
                    .orderBy(ECONOMY_EVENT.EVENT_TYPE.desc())
                    .fetch(r -> new TransactionLeg(r.value1(), r.value2(),
                            EconomyEventType.valueOf(r.value3()), Money.of(r.value4())));
        } else {
            legs = List.of(new TransactionLeg(primary.value11(), primary.value12(), primaryType,
                    Money.of(primary.value13())));
        }

        var meta = primary.value8();
        return Optional.of(new TransactionDetail(
                transactionId,
                corr == null ? null : UUID.fromString(corr),
                isTransfer ? TransactionKind.TRANSFER : TransactionKind.SINGLE,
                CurrencyCode.of(primary.value2()),
                primary.value3(),
                primary.value4(),
                primary.value5() == null ? 0 : primary.value5().intValue(),
                Money.of(primary.value6()),
                primary.value7(),
                meta == null ? null : meta.data(),
                primary.value9().toInstant(),
                legs));
    }

    @Override
    public EconomyHistoryPage findHistory(PlayerId player, Optional<CurrencyCode> currency,
            Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, int limit) {
        return queryHistory(player.value(), currency, eventType, Optional.empty(), cursorBeforeSeqNo, limit);
    }

    @Override
    public EconomyHistoryPage findServerHistory(Optional<CurrencyCode> currency,
            Optional<EconomyEventType> eventType, Optional<String> source, Long cursorBeforeSeqNo, int limit) {
        return queryHistory(null, currency, eventType, source, cursorBeforeSeqNo, limit);
    }

    /**
     * Shared keyset query for player-filtered AND server-wide history — ONE implementation, no
     * duplicated pagination. {@code playerOrNull != null} adds the player predicate (player-history);
     * {@code source} is the server-wide-only filter. Newest-first on {@code sequence_no}, fetch
     * {@code limit + 1} to decide {@code nextCursor}. Joins {@code player} for the display name.
     */
    private EconomyHistoryPage queryHistory(UUID playerOrNull, Optional<CurrencyCode> currency,
            Optional<EconomyEventType> eventType, Optional<String> source, Long cursorBeforeSeqNo, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }

        Condition where = DSL.noCondition();
        if (playerOrNull != null) {
            where = where.and(ECONOMY_EVENT.PLAYER_UUID.eq(playerOrNull));
        }
        if (currency.isPresent()) {
            where = where.and(ECONOMY_EVENT.CURRENCY_CODE.eq(currency.get().value()));
        }
        if (eventType.isPresent()) {
            where = where.and(ECONOMY_EVENT.EVENT_TYPE.eq(eventType.get().name()));
        }
        if (source.isPresent()) {
            where = where.and(ECONOMY_EVENT.SOURCE.eq(source.get()));
        }
        if (cursorBeforeSeqNo != null) {
            where = where.and(ECONOMY_EVENT.SEQUENCE_NO.lt(cursorBeforeSeqNo)); // keyset cursor (exclusive)
        }

        // correlation_id lives in the JSONB metadata; ->> yields it as text (null when absent).
        Field<String> correlationId =
                DSL.field("{0} ->> 'correlation_id'", String.class, ECONOMY_EVENT.METADATA);

        // Counterparty of a transfer = the other leg's player. The two legs share a correlation_id and
        // differ in player; a correlated subquery picks the opposite leg. NULL for non-transfer rows
        // (no correlation_id → no match). No schema column needed; works on existing data.
        Field<UUID> counterparty = DSL.field(
                "(select o.player_uuid from economy_event o where o.metadata ->> 'correlation_id' = {0}"
                        + " and o.player_uuid <> {1} limit 1)",
                UUID.class, correlationId, ECONOMY_EVENT.PLAYER_UUID);

        // Newest-first keyset; fetch one extra row to decide whether an older page exists. Server-wide
        // uses idx_event_seq_desc (V16); player-filtered uses idx_event_player_currency.
        var records = dsl.select(ECONOMY_EVENT.SEQUENCE_NO, ECONOMY_EVENT.CURRENCY_CODE,
                        ECONOMY_EVENT.EVENT_TYPE, ECONOMY_EVENT.AMOUNT, ECONOMY_EVENT.BALANCE_AFTER,
                        ECONOMY_EVENT.TRANSACTION_ID, ECONOMY_EVENT.SOURCE, correlationId, counterparty,
                        ECONOMY_EVENT.CREATED_AT, ECONOMY_EVENT.PLAYER_UUID, PLAYER.NAME)
                .from(ECONOMY_EVENT)
                .join(PLAYER).on(ECONOMY_EVENT.PLAYER_UUID.eq(PLAYER.UUID))
                .where(where)
                .orderBy(ECONOMY_EVENT.SEQUENCE_NO.desc())
                .limit(limit + 1)
                .fetch();

        boolean hasMore = records.size() > limit;
        int pageSize = Math.min(records.size(), limit);
        List<EconomyHistoryEntry> entries = new ArrayList<>(pageSize);
        for (int i = 0; i < pageSize; i++) {
            var r = records.get(i);
            entries.add(new EconomyHistoryEntry(
                    r.value1(),
                    CurrencyCode.of(r.value2()),
                    EconomyEventType.valueOf(r.value3()),
                    Money.of(r.value4()),
                    Money.of(r.value5()),
                    TransactionId.of(r.value6()),
                    r.value7(),
                    r.value8() == null ? null : UUID.fromString(r.value8()),
                    r.value9(),
                    r.value10().toInstant(),
                    r.value11(),
                    r.value12()));
        }
        // When more rows exist, the next page starts strictly below the last entry's sequence_no.
        Long nextCursor = hasMore ? entries.get(entries.size() - 1).sequenceNo() : null;
        return new EconomyHistoryPage(entries, nextCursor);
    }
}
