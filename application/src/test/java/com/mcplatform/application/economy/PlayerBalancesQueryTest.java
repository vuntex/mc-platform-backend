package com.mcplatform.application.economy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.application.economy.port.CirculationStats;
import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.application.economy.port.ProjectedBalance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.player.PlayerId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit test for the player-balances read use case: pass-through and empty result. */
class PlayerBalancesQueryTest {

    private final PlayerId alice = PlayerId.of(UUID.randomUUID());
    private final CurrencyCode coins = CurrencyCode.of("COINS");

    @Test
    void returnsBalancesFromStore() {
        FakeReadStore store = new FakeReadStore(
                List.of(new ProjectedBalance(coins, "Coins", "$", 0, Money.of(100))));
        PlayerBalancesQuery query = new PlayerBalancesQuery(store);

        List<ProjectedBalance> result = query.balances(alice);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currency()).isEqualTo(coins);
        assertThat(result.get(0).balance()).isEqualTo(Money.of(100));
        assertThat(store.lastPlayer).isEqualTo(alice);
    }

    @Test
    void emptyWhenStoreHasNoRows() {
        PlayerBalancesQuery query = new PlayerBalancesQuery(new FakeReadStore(List.of()));
        assertThat(query.balances(alice)).isEmpty();
    }

    /** Records the queried player; other reads are unused here. */
    private static final class FakeReadStore implements EconomyReadStore {
        private final List<ProjectedBalance> balances;
        PlayerId lastPlayer;

        FakeReadStore(List<ProjectedBalance> balances) {
            this.balances = balances;
        }

        @Override
        public List<ProjectedBalance> playerBalances(PlayerId player) {
            this.lastPlayer = player;
            return balances;
        }

        @Override
        public List<CirculationStats> circulation() {
            return List.of();
        }

        @Override
        public EconomyHistoryPage findHistory(PlayerId player, Optional<CurrencyCode> currency,
                Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyHistoryPage findServerHistory(Optional<CurrencyCode> currency,
                Optional<EconomyEventType> eventType, Optional<String> source, Long cursorBeforeSeqNo, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.mcplatform.application.economy.port.TransactionDetail> findTransaction(
                com.mcplatform.domain.economy.TransactionId transactionId) {
            throw new UnsupportedOperationException();
        }
    }
}
