package com.mcplatform.application.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.economy.port.AppendResult;
import com.mcplatform.application.economy.port.BalanceCachePort;
import com.mcplatform.application.economy.port.BalanceEventPublisher;
import com.mcplatform.application.economy.port.ConcurrencyConflictException;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.application.economy.port.TransferResult;
import com.mcplatform.domain.economy.AppliedEconomyEvent;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.InsufficientFundsException;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.economy.PendingEconomyEvent;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EconomyServiceTest {

    private final PlayerId alice = PlayerId.of(UUID.randomUUID());
    private final PlayerId bob = PlayerId.of(UUID.randomUUID());
    private final CurrencyCode coins = CurrencyCode.of("COINS");

    @Test
    void creditThenDebitMovesBalanceAndNotifies() {
        FakeStore store = new FakeStore();
        FakeCache cache = new FakeCache();
        FakePublisher publisher = new FakePublisher();
        EconomyService service = new EconomyService(store, cache, publisher);

        Balance afterCredit = service.credit(alice, coins, Money.of(100), TransactionId.random(), "TEST");
        assertThat(afterCredit.amount()).isEqualTo(Money.of(100));

        Balance afterDebit = service.debit(alice, coins, Money.of(40), TransactionId.random(), "TEST");
        assertThat(afterDebit.amount()).isEqualTo(Money.of(60));

        assertThat(cache.byKey).containsValue(afterDebit);
        assertThat(publisher.events).hasSize(2);
        assertThat(afterDebit.version()).isGreaterThan(afterCredit.version());
    }

    @Test
    void debitBeyondBalancePropagatesAndWritesNothing() {
        FakeStore store = new FakeStore();
        EconomyService service = new EconomyService(store, new FakeCache(), new FakePublisher());

        assertThatThrownBy(() -> service.debit(alice, coins, Money.of(10), TransactionId.random(), "TEST"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(store.appendCalls).isZero();
    }

    @Test
    void retriesOnConcurrencyConflictThenSucceeds() {
        FakeStore store = new FakeStore();
        store.failNextWrites = 2;
        EconomyService service = new EconomyService(store, new FakeCache(), new FakePublisher());

        Balance result = service.credit(alice, coins, Money.of(25), TransactionId.random(), "TEST");

        assertThat(result.amount()).isEqualTo(Money.of(25));
        assertThat(store.appendCalls).isEqualTo(3);
    }

    @Test
    void debitReplayIsIdempotentAndDoesNotReRunDomain() {
        FakeStore store = new FakeStore();
        EconomyService service = new EconomyService(store, new FakeCache(), new FakePublisher());
        service.credit(alice, coins, Money.of(100), TransactionId.random(), "TEST");

        TransactionId tx = TransactionId.random();
        Balance first = service.debit(alice, coins, Money.of(100), tx, "TEST"); // balance -> 0
        // Replaying the SAME debit must return the recorded result, not fail the funds check on 0.
        Balance replay = service.debit(alice, coins, Money.of(100), tx, "TEST");

        assertThat(first.amount()).isEqualTo(Money.of(0));
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void transferMovesMoneyBetweenPlayersAndIsIdempotent() {
        FakeStore store = new FakeStore();
        FakePublisher publisher = new FakePublisher();
        EconomyService service = new EconomyService(store, new FakeCache(), publisher);
        service.credit(alice, coins, Money.of(100), TransactionId.random(), "TEST");

        TransferId correlation = TransferId.random();
        TransferOutcome outcome = service.transfer(alice, bob, coins, Money.of(30), correlation, "WEB:transfer");

        assertThat(outcome.from().amount()).isEqualTo(Money.of(70));
        assertThat(outcome.to().amount()).isEqualTo(Money.of(30));
        assertThat(publisher.events).hasSize(3); // credit + 2 transfer legs

        TransferOutcome replay = service.transfer(alice, bob, coins, Money.of(30), correlation, "WEB:transfer");
        assertThat(replay.from().amount()).isEqualTo(Money.of(70));
        assertThat(replay.to().amount()).isEqualTo(Money.of(30));
        assertThat(publisher.events).as("replay does not publish again").hasSize(3);
    }

    // --- fakes -------------------------------------------------------------

    private static String key(PlayerId player, CurrencyCode currency) {
        return player.value() + "|" + currency.value();
    }

    private static final class FakeStore implements EconomyEventStore {
        final Map<String, Balance> balances = new HashMap<>();
        final Map<UUID, AppendResult> byTx = new HashMap<>();
        long seq = 0;
        int appendCalls = 0;
        int currentCalls = 0;
        int failNextWrites = 0;

        @Override
        public Balance currentBalance(PlayerId player, CurrencyCode currency) {
            currentCalls++;
            return balances.getOrDefault(key(player, currency), Balance.initial(player, currency));
        }

        @Override
        public void ensureZeroBalance(PlayerId player, CurrencyCode currency) {
            balances.putIfAbsent(key(player, currency), Balance.initial(player, currency));
        }

        @Override
        public AppendResult append(PendingEconomyEvent event, long expectedVersion) {
            appendCalls++;
            if (failNextWrites > 0) {
                failNextWrites--;
                throw new ConcurrencyConflictException("simulated conflict");
            }
            String k = key(event.player(), event.currency());
            if (versionOf(k) != expectedVersion) {
                throw new ConcurrencyConflictException("version mismatch");
            }
            long version = ++seq;
            balances.put(k, new Balance(event.player(), event.currency(), event.balanceAfter(), version));
            AppendResult result = new AppendResult(version, event.balanceAfter(), Instant.EPOCH, false);
            byTx.put(event.transactionId().value(), result);
            return result;
        }

        @Override
        public TransferResult transfer(PendingEconomyEvent out, long expectedFromVersion,
                PendingEconomyEvent in, long expectedToVersion) {
            if (failNextWrites > 0) {
                failNextWrites--;
                throw new ConcurrencyConflictException("simulated conflict");
            }
            String kf = key(out.player(), out.currency());
            String kt = key(in.player(), in.currency());
            if (versionOf(kf) != expectedFromVersion || versionOf(kt) != expectedToVersion) {
                throw new ConcurrencyConflictException("version mismatch");
            }
            long seqOut = ++seq;
            balances.put(kf, new Balance(out.player(), out.currency(), out.balanceAfter(), seqOut));
            long seqIn = ++seq;
            balances.put(kt, new Balance(in.player(), in.currency(), in.balanceAfter(), seqIn));
            AppendResult rOut = new AppendResult(seqOut, out.balanceAfter(), Instant.EPOCH, false);
            AppendResult rIn = new AppendResult(seqIn, in.balanceAfter(), Instant.EPOCH, false);
            byTx.put(out.transactionId().value(), rOut);
            byTx.put(in.transactionId().value(), rIn);
            return new TransferResult(rOut, rIn);
        }

        @Override
        public Optional<AppendResult> findByTransactionId(TransactionId transactionId) {
            return Optional.ofNullable(byTx.get(transactionId.value()));
        }

        @Override
        public Optional<TransferResult> findTransfer(TransferId correlationId) {
            AppendResult out = byTx.get(correlationId.outboundTransactionId().value());
            AppendResult in = byTx.get(correlationId.inboundTransactionId().value());
            return (out != null && in != null) ? Optional.of(new TransferResult(out, in)) : Optional.empty();
        }

        @Override
        public EconomyHistoryPage findHistory(PlayerId player, Optional<CurrencyCode> currency,
                Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, int limit) {
            throw new UnsupportedOperationException("history not exercised by EconomyServiceTest");
        }

        private long versionOf(String key) {
            Balance b = balances.get(key);
            return b == null ? 0 : b.version();
        }
    }

    private static final class FakeCache implements BalanceCachePort {
        final Map<String, Balance> byKey = new HashMap<>();

        @Override
        public Optional<Balance> find(PlayerId player, CurrencyCode currency) {
            return Optional.ofNullable(byKey.get(key(player, currency)));
        }

        @Override
        public void update(Balance balance) {
            byKey.put(key(balance.player(), balance.currency()), balance);
        }

        @Override
        public void evict(PlayerId player, CurrencyCode currency) {
            byKey.remove(key(player, currency));
        }
    }

    private static final class FakePublisher implements BalanceEventPublisher {
        final List<AppliedEconomyEvent> events = new ArrayList<>();

        @Override
        public void publish(AppliedEconomyEvent event) {
            events.add(event);
        }
    }
}
