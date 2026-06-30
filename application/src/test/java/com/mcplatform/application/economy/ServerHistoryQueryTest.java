package com.mcplatform.application.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.economy.port.CirculationStats;
import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.application.economy.port.ProjectedBalance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.player.PlayerId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit test for the server-wide history use case: limit clamping (reused) and filter pass-through. */
class ServerHistoryQueryTest {

    private final CurrencyCode coins = CurrencyCode.of("COINS");

    @Test
    void absentLimitUsesDefaultAndPassesFiltersThrough() {
        RecordingStore store = new RecordingStore();
        ServerHistoryQuery query = new ServerHistoryQuery(store);

        query.history(Optional.of(coins), Optional.of(EconomyEventType.TRANSFER_OUT),
                Optional.of("WEBSHOP"), 42L, null);

        assertThat(store.lastLimit).isEqualTo(EconomyHistoryService.DEFAULT_LIMIT);
        assertThat(store.lastCurrency).contains(coins);
        assertThat(store.lastType).contains(EconomyEventType.TRANSFER_OUT);
        assertThat(store.lastSource).contains("WEBSHOP");
        assertThat(store.lastCursor).isEqualTo(42L);
    }

    @Test
    void oversizedLimitIsClampedAndNonPositiveRejected() {
        RecordingStore store = new RecordingStore();
        ServerHistoryQuery query = new ServerHistoryQuery(store);

        query.history(Optional.empty(), Optional.empty(), Optional.empty(), null, 10_000);
        assertThat(store.lastLimit).isEqualTo(EconomyHistoryService.MAX_LIMIT);

        assertThatThrownBy(() -> query.history(Optional.empty(), Optional.empty(), Optional.empty(), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingStore implements EconomyReadStore {
        Optional<CurrencyCode> lastCurrency;
        Optional<EconomyEventType> lastType;
        Optional<String> lastSource;
        Long lastCursor;
        int lastLimit;

        @Override
        public EconomyHistoryPage findServerHistory(Optional<CurrencyCode> currency,
                Optional<EconomyEventType> eventType, Optional<String> source, Long cursorBeforeSeqNo, int limit) {
            this.lastCurrency = currency;
            this.lastType = eventType;
            this.lastSource = source;
            this.lastCursor = cursorBeforeSeqNo;
            this.lastLimit = limit;
            return new EconomyHistoryPage(List.of(), null);
        }

        @Override
        public EconomyHistoryPage findHistory(PlayerId player, Optional<CurrencyCode> currency,
                Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CirculationStats> circulation() {
            return List.of();
        }

        @Override
        public List<ProjectedBalance> playerBalances(PlayerId player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.mcplatform.application.economy.port.TransactionDetail> findTransaction(
                com.mcplatform.domain.economy.TransactionId transactionId) {
            throw new UnsupportedOperationException();
        }
    }
}
