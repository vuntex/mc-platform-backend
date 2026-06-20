package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.ECONOMY_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.economy.port.AppendResult;
import com.mcplatform.application.economy.port.ConcurrencyConflictException;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.economy.PendingEconomyEvent;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.Transfer;
import com.mcplatform.domain.economy.TransferEvents;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.application.economy.port.TransferResult;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration test for the jOOQ economy adapter against a real, Flyway-migrated Postgres. */
@Testcontainers
class JooqEconomyRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqEconomyRepository economy;
    static JooqPlayerRepository players;

    private final CurrencyCode coins = CurrencyCode.of("COINS");

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        economy = new JooqEconomyRepository(dsl);
        players = new JooqPlayerRepository(dsl);
    }

    private PlayerId newPlayer() {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, "Steve", Instant.now());
        return p;
    }

    @Test
    void creditThenDebitProjectsBalanceAndVersion() {
        PlayerId p = newPlayer();

        Balance b0 = economy.currentBalance(p, coins);
        assertThat(b0.amount()).isEqualTo(Money.of(0));
        assertThat(b0.version()).isZero();

        AppendResult credit = economy.append(b0.credit(Money.of(100), TransactionId.random(), "TEST"), b0.version());
        assertThat(credit.balanceAfter()).isEqualTo(Money.of(100));
        assertThat(credit.version()).isPositive();
        assertThat(credit.idempotentReplay()).isFalse();

        Balance b1 = economy.currentBalance(p, coins);
        AppendResult debit = economy.append(b1.debit(Money.of(30), TransactionId.random(), "TEST"), b1.version());
        assertThat(debit.balanceAfter()).isEqualTo(Money.of(70));
        assertThat(debit.version()).isGreaterThan(credit.version());

        assertThat(economy.currentBalance(p, coins).amount()).isEqualTo(Money.of(70));
    }

    @Test
    void staleVersionIsRejected() {
        PlayerId p = newPlayer();

        Balance b0 = economy.currentBalance(p, coins);
        economy.append(b0.credit(Money.of(10), TransactionId.random(), "TEST"), b0.version()); // advances version

        // Reusing the now-stale b0.version() must conflict.
        PendingEconomyEvent stale = b0.credit(Money.of(10), TransactionId.random(), "TEST");
        assertThatThrownBy(() -> economy.append(stale, b0.version()))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void duplicateTransactionIdIsIdempotent() {
        PlayerId p = newPlayer();

        Balance b0 = economy.currentBalance(p, coins);
        TransactionId tx = TransactionId.random();
        PendingEconomyEvent event = b0.credit(Money.of(55), tx, "TEST");

        AppendResult first = economy.append(event, b0.version());
        AppendResult replay = economy.append(event, b0.version());

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(replay.balanceAfter()).isEqualTo(Money.of(55));

        long eventRows = dsl.fetchCount(ECONOMY_EVENT, ECONOMY_EVENT.TRANSACTION_ID.eq(tx.value()));
        assertThat(eventRows).as("only one event row for the transaction id").isEqualTo(1);
        assertThat(economy.currentBalance(p, coins).amount()).isEqualTo(Money.of(55));
    }

    @Test
    void transferMovesMoneyAtomicallyWithSharedCorrelationId() {
        PlayerId from = newPlayer();
        PlayerId to = newPlayer();

        Balance funded = economy.currentBalance(from, coins);
        economy.append(funded.credit(Money.of(100), TransactionId.random(), "TEST"), funded.version());

        Balance balanceFrom = economy.currentBalance(from, coins);
        Balance balanceTo = economy.currentBalance(to, coins);
        TransferId correlation = TransferId.random();
        TransferEvents events = Transfer.prepare(balanceFrom, balanceTo, Money.of(30), correlation, "WEB:transfer");

        TransferResult result = economy.transfer(
                events.out(), balanceFrom.version(), events.in(), balanceTo.version());

        assertThat(result.out().balanceAfter()).isEqualTo(Money.of(70));
        assertThat(result.in().balanceAfter()).isEqualTo(Money.of(30));
        assertThat(economy.currentBalance(from, coins).amount()).isEqualTo(Money.of(70));
        assertThat(economy.currentBalance(to, coins).amount()).isEqualTo(Money.of(30));

        long legsWithCorrelation = dsl.fetchCount(ECONOMY_EVENT,
                DSL.field("{0} ->> 'correlation_id'", String.class, ECONOMY_EVENT.METADATA)
                        .eq(correlation.value().toString()));
        assertThat(legsWithCorrelation).as("both legs tagged with the correlation id").isEqualTo(2);

        assertThat(economy.findTransfer(correlation)).isPresent();
    }

    @Test
    void transferIsIdempotentOnReplay() {
        PlayerId from = newPlayer();
        PlayerId to = newPlayer();
        Balance funded = economy.currentBalance(from, coins);
        economy.append(funded.credit(Money.of(100), TransactionId.random(), "TEST"), funded.version());

        Balance balanceFrom = economy.currentBalance(from, coins);
        Balance balanceTo = economy.currentBalance(to, coins);
        TransferId correlation = TransferId.random();
        TransferEvents events = Transfer.prepare(balanceFrom, balanceTo, Money.of(40), correlation, "WEB:transfer");

        economy.transfer(events.out(), balanceFrom.version(), events.in(), balanceTo.version());
        // Replaying the same legs returns the recorded result instead of writing again.
        TransferResult replay = economy.transfer(events.out(), balanceFrom.version(), events.in(), balanceTo.version());

        assertThat(replay.out().idempotentReplay()).isTrue();
        assertThat(replay.in().idempotentReplay()).isTrue();
        assertThat(economy.currentBalance(from, coins).amount()).isEqualTo(Money.of(60));
        assertThat(economy.currentBalance(to, coins).amount()).isEqualTo(Money.of(40));
    }

    @Test
    void concurrentDuplicateAppendsApplyOnceAndNeverError() throws Exception {
        PlayerId p = newPlayer();
        Balance b0 = economy.currentBalance(p, coins);
        PendingEconomyEvent event = b0.credit(Money.of(100), TransactionId.random(), "TEST");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<AppendResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return economy.append(event, b0.version());
            }));
        }
        List<AppendResult> results = new ArrayList<>();
        for (Future<AppendResult> f : futures) {
            results.add(f.get(20, TimeUnit.SECONDS)); // none may throw
        }
        pool.shutdown();

        long version = results.get(0).version();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.version()).isEqualTo(version);
            assertThat(r.balanceAfter()).isEqualTo(Money.of(100));
        });
        assertThat(dsl.fetchCount(ECONOMY_EVENT, ECONOMY_EVENT.TRANSACTION_ID.eq(event.transactionId().value())))
                .as("event written exactly once").isEqualTo(1);
        assertThat(economy.currentBalance(p, coins).amount()).isEqualTo(Money.of(100));
    }

    @Test
    void concurrentDuplicateTransfersApplyOnceAndNeverError() throws Exception {
        PlayerId from = newPlayer();
        PlayerId to = newPlayer();
        Balance funded = economy.currentBalance(from, coins);
        economy.append(funded.credit(Money.of(100), TransactionId.random(), "TEST"), funded.version());

        Balance balanceFrom = economy.currentBalance(from, coins);
        Balance balanceTo = economy.currentBalance(to, coins);
        TransferId correlation = TransferId.random();
        TransferEvents events = Transfer.prepare(balanceFrom, balanceTo, Money.of(30), correlation, "WEB:transfer");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<TransferResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return economy.transfer(events.out(), balanceFrom.version(), events.in(), balanceTo.version());
            }));
        }
        for (Future<TransferResult> f : futures) {
            f.get(20, TimeUnit.SECONDS); // none may throw
        }
        pool.shutdown();

        assertThat(economy.currentBalance(from, coins).amount()).isEqualTo(Money.of(70));
        assertThat(economy.currentBalance(to, coins).amount()).isEqualTo(Money.of(30));
        assertThat(dsl.fetchCount(ECONOMY_EVENT,
                DSL.field("{0} ->> 'correlation_id'", String.class, ECONOMY_EVENT.METADATA)
                        .eq(correlation.value().toString())))
                .as("transfer applied exactly once (two legs)").isEqualTo(2);
    }
}
