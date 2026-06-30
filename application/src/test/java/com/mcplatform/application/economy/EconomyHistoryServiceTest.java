package com.mcplatform.application.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.economy.port.AppendResult;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.application.economy.port.TransferResult;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.PendingEconomyEvent;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.domain.player.PlayerId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the read-only history use case: limit clamping/default and criteria pass-through. */
class EconomyHistoryServiceTest {

    private final PlayerId alice = PlayerId.of(UUID.randomUUID());
    private final CurrencyCode coins = CurrencyCode.of("COINS");

    @Test
    void absentLimitUsesDefault() {
        RecordingStore store = new RecordingStore();
        EconomyHistoryService service = new EconomyHistoryService(store);

        service.history(alice, Optional.empty(), Optional.empty(), null, null);

        assertThat(store.lastLimit).isEqualTo(EconomyHistoryService.DEFAULT_LIMIT);
    }

    @Test
    void oversizedLimitIsClampedToMax() {
        RecordingStore store = new RecordingStore();
        EconomyHistoryService service = new EconomyHistoryService(store);

        service.history(alice, Optional.empty(), Optional.empty(), null, 10_000);

        assertThat(store.lastLimit).isEqualTo(EconomyHistoryService.MAX_LIMIT);
    }

    @Test
    void requestedLimitWithinBoundsIsPassedThrough() {
        RecordingStore store = new RecordingStore();
        EconomyHistoryService service = new EconomyHistoryService(store);

        service.history(alice, Optional.empty(), Optional.empty(), null, 25);

        assertThat(store.lastLimit).isEqualTo(25);
    }

    @Test
    void nonPositiveLimitIsRejected() {
        EconomyHistoryService service = new EconomyHistoryService(new RecordingStore());

        assertThatThrownBy(() -> service.history(alice, Optional.empty(), Optional.empty(), null, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history(alice, Optional.empty(), Optional.empty(), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursorAndFiltersArePassedThroughUnchanged() {
        RecordingStore store = new RecordingStore();
        EconomyHistoryService service = new EconomyHistoryService(store);

        service.history(alice, Optional.of(coins), Optional.of(EconomyEventType.DEBITED), 99L, 50);

        assertThat(store.lastPlayer).isEqualTo(alice);
        assertThat(store.lastCurrency).contains(coins);
        assertThat(store.lastType).contains(EconomyEventType.DEBITED);
        assertThat(store.lastCursor).isEqualTo(99L);
    }

    /** Records the last findHistory arguments; other operations are unused here. */
    private static final class RecordingStore implements EconomyEventStore {
        PlayerId lastPlayer;
        Optional<CurrencyCode> lastCurrency;
        Optional<EconomyEventType> lastType;
        Long lastCursor;
        int lastLimit;

        @Override
        public java.util.List<com.mcplatform.application.economy.port.CirculationStats> circulation() {
            return java.util.List.of();
        }

        @Override
        public EconomyHistoryPage findHistory(PlayerId player, Optional<CurrencyCode> currency,
                Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, int limit) {
            this.lastPlayer = player;
            this.lastCurrency = currency;
            this.lastType = eventType;
            this.lastCursor = cursorBeforeSeqNo;
            this.lastLimit = limit;
            return new EconomyHistoryPage(List.of(), null);
        }

        @Override
        public Balance currentBalance(PlayerId player, CurrencyCode currency) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensureZeroBalance(PlayerId player, CurrencyCode currency) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AppendResult append(PendingEconomyEvent event, long expectedVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransferResult transfer(PendingEconomyEvent out, long expectedFromVersion,
                PendingEconomyEvent in, long expectedToVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AppendResult> findByTransactionId(TransactionId transactionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TransferResult> findTransfer(TransferId correlationId) {
            throw new UnsupportedOperationException();
        }
    }
}
