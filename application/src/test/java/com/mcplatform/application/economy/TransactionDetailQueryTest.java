package com.mcplatform.application.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit test for the transaction-detail use case: pass-through and the not-found → exception path. */
class TransactionDetailQueryTest {

    @Test
    void returnsDetailFromStore() {
        TransactionId tx = TransactionId.random();
        TransactionDetail detail = new TransactionDetail(tx, null, TransactionKind.SINGLE,
                CurrencyCode.of("COINS"), "Coins", "$", 0, Money.of(10), "WEB", null, Instant.EPOCH,
                List.of(new TransactionLeg(UUID.randomUUID(), "Steve", EconomyEventType.CREDITED, Money.of(10))));
        TransactionDetailQuery query = new TransactionDetailQuery(new FakeReadStore(Optional.of(detail)));

        assertThat(query.detail(tx)).isSameAs(detail);
    }

    @Test
    void throwsWhenStoreHasNoTransaction() {
        TransactionDetailQuery query = new TransactionDetailQuery(new FakeReadStore(Optional.empty()));
        assertThatThrownBy(() -> query.detail(TransactionId.random()))
                .isInstanceOf(EconomyTransactionNotFoundException.class);
    }

    private record FakeReadStore(Optional<TransactionDetail> detail) implements EconomyReadStore {
        @Override
        public Optional<TransactionDetail> findTransaction(TransactionId transactionId) {
            return detail;
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
        public List<CirculationStats> circulation() {
            return List.of();
        }

        @Override
        public List<ProjectedBalance> playerBalances(PlayerId player) {
            throw new UnsupportedOperationException();
        }
    }
}
