package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.ECONOMY_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.economy.EconomyHistoryEntry;
import com.mcplatform.application.economy.EconomyHistoryPage;
import com.mcplatform.application.economy.port.AppendResult;
import com.mcplatform.application.economy.port.ProjectedBalance;
import com.mcplatform.application.economy.port.TransactionDetail;
import com.mcplatform.application.economy.port.TransactionKind;
import com.mcplatform.application.economy.port.TransactionLeg;
import com.mcplatform.application.economy.port.ConcurrencyConflictException;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
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
import java.util.Optional;
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
    static JooqEconomyReadStore readStore;
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
        readStore = new JooqEconomyReadStore(dsl);
        players = new JooqPlayerRepository(dsl);
    }

    private PlayerId newPlayer() {
        return newNamedPlayer("Steve");
    }

    private PlayerId newNamedPlayer(String name) {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, name, Instant.now());
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
    void appendOnExistingVersionZeroRowSucceeds() {
        PlayerId p = newPlayer();
        economy.ensureZeroBalance(p, coins); // materialises the projection row AT version 0

        Balance b0 = economy.currentBalance(p, coins);
        assertThat(b0.version()).isZero();
        assertThat(b0.amount()).isEqualTo(Money.of(0));

        // Regression: a row already at version 0 must be writable. Previously the expectedVersion==0
        // branch only INSERTed, so it conflicted forever (409 concurrency_conflict) and the player was
        // permanently stuck on every economy write.
        AppendResult credit = economy.append(b0.credit(Money.of(50_000), TransactionId.random(), "TEST"), b0.version());
        assertThat(credit.balanceAfter()).isEqualTo(Money.of(50_000));
        assertThat(credit.version()).isPositive();
        assertThat(economy.currentBalance(p, coins).amount()).isEqualTo(Money.of(50_000));
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

    // --- read-only history query -------------------------------------------

    @Test
    void historyReturnsEventsNewestFirstWithDirectionAndBalance() {
        PlayerId p = newPlayer();
        credit(p, coins, 100);
        debit(p, coins, 30);
        set(p, coins, 200);

        EconomyHistoryPage page = readStore.findHistory(p, Optional.empty(), Optional.empty(), null, 50);

        assertThat(page.nextCursor()).isNull();
        assertThat(page.entries()).extracting(EconomyHistoryEntry::type)
                .containsExactly(EconomyEventType.SET, EconomyEventType.DEBITED, EconomyEventType.CREDITED);
        // Newest-first ⇒ strictly descending sequence_no.
        assertThat(page.entries()).extracting(EconomyHistoryEntry::sequenceNo).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        EconomyHistoryEntry newest = page.entries().get(0);
        assertThat(newest.amount()).isEqualTo(Money.of(200));
        assertThat(newest.balanceAfter()).isEqualTo(Money.of(200));
        assertThat(newest.currency()).isEqualTo(coins);
        assertThat(newest.source()).isEqualTo("TEST");
        assertThat(newest.correlationId()).as("non-transfer events carry no correlation id").isNull();
    }

    @Test
    void historyFiltersByCurrency() {
        dsl.execute("INSERT INTO currency (code, display_name, symbol, decimal_places, default_balance) "
                + "VALUES ('GEMS', 'Gems', NULL, 0, 0) ON CONFLICT (code) DO NOTHING");
        CurrencyCode gems = CurrencyCode.of("GEMS");
        PlayerId p = newPlayer();
        credit(p, coins, 100);
        credit(p, gems, 5);
        credit(p, coins, 10);

        EconomyHistoryPage coinsOnly = readStore.findHistory(p, Optional.of(coins), Optional.empty(), null, 50);

        assertThat(coinsOnly.entries()).hasSize(2);
        assertThat(coinsOnly.entries()).allSatisfy(e -> assertThat(e.currency()).isEqualTo(coins));
    }

    @Test
    void historyFiltersByEventType() {
        PlayerId p = newPlayer();
        credit(p, coins, 100);
        debit(p, coins, 10);
        debit(p, coins, 20);

        EconomyHistoryPage debitsOnly =
                readStore.findHistory(p, Optional.empty(), Optional.of(EconomyEventType.DEBITED), null, 50);

        assertThat(debitsOnly.entries()).hasSize(2);
        assertThat(debitsOnly.entries()).allSatisfy(e -> assertThat(e.type()).isEqualTo(EconomyEventType.DEBITED));
    }

    @Test
    void historyKeysetPaginatesWithoutGapsOrOverlap() {
        PlayerId p = newPlayer();
        for (int i = 0; i < 5; i++) {
            credit(p, coins, 10);
        }

        // Walk every page with limit 2 and accumulate.
        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            EconomyHistoryPage page = readStore.findHistory(p, Optional.empty(), Optional.empty(), cursor, 2);
            assertThat(page.entries().size()).isLessThanOrEqualTo(2);
            page.entries().forEach(e -> seen.add(e.sequenceNo()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(cursor).as("last page reports no further cursor").isNull();
        assertThat(seen).as("all 5 events, no gaps").hasSize(5);
        assertThat(seen).as("no overlap between pages").doesNotHaveDuplicates();
        assertThat(seen).as("globally newest-first across pages").isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    void historyNextCursorPointsToLastReturnedEntry() {
        PlayerId p = newPlayer();
        for (int i = 0; i < 3; i++) {
            credit(p, coins, 10);
        }

        EconomyHistoryPage firstPage = readStore.findHistory(p, Optional.empty(), Optional.empty(), null, 2);

        assertThat(firstPage.entries()).hasSize(2);
        assertThat(firstPage.nextCursor())
                .isEqualTo(firstPage.entries().get(firstPage.entries().size() - 1).sequenceNo());
        // The next page starts strictly below the cursor and yields the remaining (oldest) event.
        EconomyHistoryPage secondPage =
                readStore.findHistory(p, Optional.empty(), Optional.empty(), firstPage.nextCursor(), 2);
        assertThat(secondPage.entries()).hasSize(1);
        assertThat(secondPage.entries().get(0).sequenceNo()).isLessThan(firstPage.nextCursor());
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void historyReadsCorrelationIdFromBothTransferLegs() {
        PlayerId from = newPlayer();
        PlayerId to = newPlayer();
        credit(from, coins, 100);

        Balance balanceFrom = economy.currentBalance(from, coins);
        Balance balanceTo = economy.currentBalance(to, coins);
        TransferId correlation = TransferId.random();
        TransferEvents events = Transfer.prepare(balanceFrom, balanceTo, Money.of(30), correlation, "WEB:transfer");
        economy.transfer(events.out(), balanceFrom.version(), events.in(), balanceTo.version());

        EconomyHistoryEntry outLeg = readStore.findHistory(from, Optional.empty(),
                Optional.of(EconomyEventType.TRANSFER_OUT), null, 50).entries().get(0);
        EconomyHistoryEntry inLeg = readStore.findHistory(to, Optional.empty(),
                Optional.of(EconomyEventType.TRANSFER_IN), null, 50).entries().get(0);

        assertThat(outLeg.correlationId()).isEqualTo(correlation.value());
        assertThat(inLeg.correlationId()).isEqualTo(correlation.value());
        assertThat(outLeg.correlationId()).as("both legs share one correlation id").isEqualTo(inLeg.correlationId());

        // Counterparty is the OTHER leg's player: the OUT leg points at the receiver, the IN leg at the sender.
        assertThat(outLeg.counterpartyUuid()).as("OUT leg → receiver").isEqualTo(to.value());
        assertThat(inLeg.counterpartyUuid()).as("IN leg → sender").isEqualTo(from.value());

        EconomyHistoryEntry plainCredit = readStore.findHistory(from, Optional.empty(),
                Optional.of(EconomyEventType.CREDITED), null, 50).entries().get(0);
        assertThat(plainCredit.counterpartyUuid()).as("non-transfer events have no counterparty").isNull();
    }

    // --- read-only player balances (US1) -----------------------------------

    @Test
    void playerBalancesReturnsAllCurrenciesWithDisplayMetadata() {
        dsl.execute("INSERT INTO currency (code, display_name, symbol, decimal_places, default_balance) "
                + "VALUES ('GEMS', 'Gems', NULL, 0, 0) ON CONFLICT (code) DO NOTHING");
        CurrencyCode gems = CurrencyCode.of("GEMS");
        PlayerId p = newPlayer();
        credit(p, coins, 100);
        credit(p, gems, 5);

        List<ProjectedBalance> balances = readStore.playerBalances(p);

        assertThat(balances).hasSize(2);
        ProjectedBalance gemsBal = balances.stream()
                .filter(b -> b.currency().equals(gems)).findFirst().orElseThrow();
        assertThat(gemsBal.displayName()).isEqualTo("Gems");
        assertThat(gemsBal.symbol()).isNull();
        assertThat(gemsBal.decimalPlaces()).isZero();
        assertThat(gemsBal.balance()).isEqualTo(Money.of(5));
        ProjectedBalance coinsBal = balances.stream()
                .filter(b -> b.currency().equals(coins)).findFirst().orElseThrow();
        assertThat(coinsBal.balance()).isEqualTo(Money.of(100));
        assertThat(coinsBal.displayName()).isNotBlank();
    }

    @Test
    void playerBalancesEmptyForUnknownPlayer() {
        assertThat(readStore.playerBalances(PlayerId.of(UUID.randomUUID()))).isEmpty();
    }

    // --- server-wide history (US2) -----------------------------------------

    @Test
    void serverHistoryCarriesPlayerUuidAndNameAndIsNewestFirst() {
        PlayerId alice = newNamedPlayer("ServerHistAlice");
        credit(alice, coins, 11);

        EconomyHistoryPage page = readStore.findServerHistory(
                Optional.empty(), Optional.empty(), Optional.empty(), null, 200);

        // global newest-first across all players in the store
        assertThat(page.entries()).extracting(EconomyHistoryEntry::sequenceNo)
                .isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(page.entries()).anySatisfy(e -> {
            assertThat(e.playerUuid()).isEqualTo(alice.value());
            assertThat(e.playerName()).isEqualTo("ServerHistAlice");
        });
    }

    @Test
    void serverHistoryFiltersBySource() {
        PlayerId p = newNamedPlayer("SrcFilter");
        Balance b = economy.currentBalance(p, coins);
        economy.append(b.credit(Money.of(5), TransactionId.random(), "WEBSHOP-007"), b.version());
        credit(p, coins, 7); // a second event with the default "TEST" source

        EconomyHistoryPage webshop = readStore.findServerHistory(
                Optional.empty(), Optional.empty(), Optional.of("WEBSHOP-007"), null, 50);

        assertThat(webshop.entries()).hasSize(1);
        assertThat(webshop.entries().get(0).source()).isEqualTo("WEBSHOP-007");
        assertThat(webshop.entries().get(0).playerName()).isEqualTo("SrcFilter");
    }

    @Test
    void playerHistoryEntriesAlsoCarryPlayerUuidAndName() {
        PlayerId p = newNamedPlayer("HistName");
        credit(p, coins, 5);

        EconomyHistoryEntry e = readStore.findHistory(p, Optional.empty(), Optional.empty(), null, 50)
                .entries().get(0);
        assertThat(e.playerUuid()).isEqualTo(p.value());
        assertThat(e.playerName()).isEqualTo("HistName");
    }

    // --- transaction detail (US3) ------------------------------------------

    @Test
    void transactionDetailForSingleEventHasOneLeg() {
        PlayerId p = newNamedPlayer("DetailSingle");
        Balance b = economy.currentBalance(p, coins);
        TransactionId tx = TransactionId.random();
        economy.append(b.credit(Money.of(42), tx, "WEB"), b.version());

        TransactionDetail d = readStore.findTransaction(tx).orElseThrow();

        assertThat(d.kind()).isEqualTo(TransactionKind.SINGLE);
        assertThat(d.correlationId()).isNull();
        assertThat(d.amount()).isEqualTo(Money.of(42));
        assertThat(d.legs()).hasSize(1);
        assertThat(d.legs().get(0).playerName()).isEqualTo("DetailSingle");
        assertThat(d.legs().get(0).eventType()).isEqualTo(EconomyEventType.CREDITED);
    }

    @Test
    void transactionDetailForTransferHasTwoLegsWithBothNames() {
        PlayerId from = newNamedPlayer("DetailSender");
        PlayerId to = newNamedPlayer("DetailReceiver");
        credit(from, coins, 100);
        Balance bf = economy.currentBalance(from, coins);
        Balance bt = economy.currentBalance(to, coins);
        TransferId correlation = TransferId.random();
        TransferEvents events = Transfer.prepare(bf, bt, Money.of(30), correlation, "WEB:transfer");
        economy.transfer(events.out(), bf.version(), events.in(), bt.version());

        TransactionDetail d = readStore.findTransaction(events.out().transactionId()).orElseThrow();

        assertThat(d.kind()).isEqualTo(TransactionKind.TRANSFER);
        assertThat(d.correlationId()).isEqualTo(correlation.value());
        assertThat(d.legs()).hasSize(2);
        assertThat(d.legs()).extracting(TransactionLeg::playerName)
                .containsExactlyInAnyOrder("DetailSender", "DetailReceiver");
        assertThat(d.legs()).extracting(TransactionLeg::eventType)
                .contains(EconomyEventType.TRANSFER_OUT, EconomyEventType.TRANSFER_IN);
    }

    @Test
    void transactionDetailWithMissingCounterLegDegradesToOneLeg() {
        PlayerId from = newNamedPlayer("DegradeSender");
        PlayerId to = newNamedPlayer("DegradeReceiver");
        credit(from, coins, 100);
        Balance bf = economy.currentBalance(from, coins);
        Balance bt = economy.currentBalance(to, coins);
        TransferId correlation = TransferId.random();
        TransferEvents events = Transfer.prepare(bf, bt, Money.of(30), correlation, "WEB:transfer");
        economy.transfer(events.out(), bf.version(), events.in(), bt.version());
        // simulate inconsistency: the IN leg vanished
        dsl.deleteFrom(ECONOMY_EVENT)
                .where(ECONOMY_EVENT.TRANSACTION_ID.eq(events.in().transactionId().value()))
                .execute();

        TransactionDetail d = readStore.findTransaction(events.out().transactionId()).orElseThrow();

        assertThat(d.kind()).isEqualTo(TransactionKind.TRANSFER); // still a transfer
        assertThat(d.correlationId()).isEqualTo(correlation.value());
        assertThat(d.legs()).hasSize(1); // only the surviving leg, no error, nothing invented
        assertThat(d.legs().get(0).eventType()).isEqualTo(EconomyEventType.TRANSFER_OUT);
    }

    @Test
    void transactionDetailUnknownIdIsEmpty() {
        assertThat(readStore.findTransaction(TransactionId.random())).isEmpty();
    }

    private long credit(PlayerId p, CurrencyCode c, long amount) {
        Balance b = economy.currentBalance(p, c);
        return economy.append(b.credit(Money.of(amount), TransactionId.random(), "TEST"), b.version()).version();
    }

    private long debit(PlayerId p, CurrencyCode c, long amount) {
        Balance b = economy.currentBalance(p, c);
        return economy.append(b.debit(Money.of(amount), TransactionId.random(), "TEST"), b.version()).version();
    }

    private long set(PlayerId p, CurrencyCode c, long amount) {
        Balance b = economy.currentBalance(p, c);
        return economy.append(b.set(Money.of(amount), TransactionId.random(), "TEST"), b.version()).version();
    }
}
