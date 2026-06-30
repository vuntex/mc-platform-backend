package com.mcplatform.application.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcplatform.application.economy.port.AppendResult;
import com.mcplatform.application.economy.port.CirculationStats;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.application.economy.port.TransferResult;
import com.mcplatform.domain.economy.AppliedEconomyEvent;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.economy.PendingEconomyEvent;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.domain.player.PlayerId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Alert criteria + transfer Von/An correlation (one alert, both parties, no duplicate). */
class EconomyAlertMonitorTest {

    private static final CurrencyCode COINS = CurrencyCode.of("COINS");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private final List<EconomyAlert> alerts = new ArrayList<>();
    private EconomyAlertMonitor monitor;

    @BeforeEach
    void setUp() {
        EconomyStatsService stats = new EconomyStatsService(new FakeStore(1_000_000L, 10));
        stats.refresh();
        // circulation 1,000,000; alert if > 5% of circulation OR > 80% of sender balance; ignore < 1000.
        monitor = new EconomyAlertMonitor(stats, alerts::add, 5, 80, 1000);
    }

    private AppliedEconomyEvent event(UUID player, EconomyEventType type, long amount, long balanceAfter, UUID corr) {
        return new AppliedEconomyEvent(PlayerId.of(player), COINS, type, Money.of(amount), Money.of(balanceAfter),
                1L, TransactionId.random(), "PLUGIN:test", corr == null ? null : TransferId.of(corr),
                Instant.ofEpochMilli(1_700_000_000_000L));
    }

    /** Drives an OUT then IN leg for one transfer (same correlationId), returning after both are applied. */
    private void transfer(long amount, long senderBalanceAfter, long receiverBalanceAfter) {
        UUID corr = UUID.randomUUID();
        monitor.evaluate(event(A, EconomyEventType.TRANSFER_OUT, amount, senderBalanceAfter, corr));
        monitor.evaluate(event(B, EconomyEventType.TRANSFER_IN, amount, receiverBalanceAfter, corr));
    }

    @Test
    void systemCreditNeverAlerts() {
        // A large system/reward credit (6% of circulation) is intentional → no alert.
        monitor.evaluate(event(A, EconomyEventType.CREDITED, 60_000, 60_000, null));
        assertTrue(alerts.isEmpty());
    }

    @Test
    void adminSetNeverAlerts() {
        // An admin SET to a huge balance is intentional → no alert.
        monitor.evaluate(event(A, EconomyEventType.SET, 500_000, 500_000, null));
        assertTrue(alerts.isEmpty());
    }

    @Test
    void adminDebitNeverAlerts() {
        // 9000 = 90% of the 10000 balance-before, but an admin debit is intentional → no alert.
        monitor.evaluate(event(A, EconomyEventType.DEBITED, 9_000, 1_000, null));
        assertTrue(alerts.isEmpty());
    }

    @Test
    void transferBelowMinAmountIgnored() {
        transfer(500, 500, 500);
        assertTrue(alerts.isEmpty());
    }

    @Test
    void transferBelowAllThresholdsNoAlert() {
        // 10,000 = 1% of circulation and tiny vs. a large sender balance → no alert.
        transfer(10_000, 1_000_000, 10_000);
        assertTrue(alerts.isEmpty());
    }

    @Test
    void transferCirculationShareTriggers() {
        // 60,000 = 6% of circulation (>5%); sender balance large enough that the sender criterion stays quiet.
        transfer(60_000, 1_000_000, 60_000);
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).reason().contains("Umlauf"));
    }

    @Test
    void transferSenderBalanceShareTriggers() {
        // 9000 is 0.9% of circulation (no), but 90% of the 10000 balance-before (yes).
        transfer(9_000, 1_000, 9_000);
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).reason().contains("Sender-Guthabens"));
    }

    @Test
    void transferProducesOneAlertWithBothParties() {
        UUID corr = UUID.randomUUID();
        // 100,000 = 10% of circulation → fires. OUT then IN (same correlationId).
        monitor.evaluate(event(A, EconomyEventType.TRANSFER_OUT, 100_000, 5_000, corr));
        assertTrue(alerts.isEmpty(), "OUT alone does not alert yet (waits for IN)");
        monitor.evaluate(event(B, EconomyEventType.TRANSFER_IN, 100_000, 105_000, corr));

        assertEquals(1, alerts.size(), "exactly one alert for the transfer (no duplicate)");
        EconomyAlert a = alerts.get(0);
        assertEquals(A, a.player(), "Von = sender");
        assertEquals(B, a.target(), "An = receiver");
        assertEquals("TRANSFER", a.type());
    }

    /** Minimal store: only {@link #circulation()} is meaningful. */
    private record FakeStore(long total, int accounts) implements EconomyEventStore {
        @Override
        public List<CirculationStats> circulation() {
            return List.of(new CirculationStats(COINS, total, accounts));
        }

        @Override public Balance currentBalance(PlayerId p, CurrencyCode c) { throw new UnsupportedOperationException(); }
        @Override public void ensureZeroBalance(PlayerId p, CurrencyCode c) { throw new UnsupportedOperationException(); }
        @Override public AppendResult append(PendingEconomyEvent e, long v) { throw new UnsupportedOperationException(); }
        @Override public TransferResult transfer(PendingEconomyEvent o, long ov, PendingEconomyEvent i, long iv) { throw new UnsupportedOperationException(); }
        @Override public Optional<AppendResult> findByTransactionId(TransactionId t) { throw new UnsupportedOperationException(); }
        @Override public Optional<TransferResult> findTransfer(TransferId c) { throw new UnsupportedOperationException(); }
        @Override public EconomyHistoryPage findHistory(PlayerId p, Optional<CurrencyCode> c, Optional<EconomyEventType> t, Long cur, int l) { throw new UnsupportedOperationException(); }
    }
}
