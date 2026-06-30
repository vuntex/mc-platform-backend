package com.mcplatform.application.economy;

import com.mcplatform.application.economy.port.CirculationStats;
import com.mcplatform.application.economy.port.EconomyAlertPublisher;
import com.mcplatform.domain.economy.AppliedEconomyEvent;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flags suspiciously high <b>player-to-player transfers</b> ({@code /pay}) and publishes an alert.
 * Evaluated for every applied economy event, but only transfers are monitored: coins granted by the
 * system (rewards, join bonus → CREDITED) or by an admin (grant/SET, admin debit → DEBITED) are
 * intentional and never alert.
 *
 * <p><b>Transfers carry both parties.</b> A transfer publishes two legs back-to-back (TRANSFER_OUT then
 * TRANSFER_IN, same correlationId). The OUT leg is stashed and, when its IN leg arrives, the two are
 * combined into ONE alert with sender ("Von") and receiver ("An") — avoiding a duplicate alert and the
 * missing counterparty.
 *
 * <p>Two criteria (alert if either fires): the amount exceeds {@code circulationPercent}% of total
 * circulation, or {@code senderBalancePercent}% of the sender's balance before the transfer. Amounts
 * below {@code minAmount} are ignored. Never throws.
 */
public final class EconomyAlertMonitor {

    /** A stashed TRANSFER_OUT leg awaiting its matching TRANSFER_IN. */
    private record OutLeg(UUID from, CurrencyCode currency, long amount, long senderBalanceAfter, long occurredAt) {
    }

    private final EconomyStatsService stats;
    private final EconomyAlertPublisher publisher;
    private final int circulationPercent;
    private final int senderBalancePercent;
    private final long minAmount;
    private final Map<UUID, OutLeg> pendingOut = new ConcurrentHashMap<>();

    public EconomyAlertMonitor(EconomyStatsService stats, EconomyAlertPublisher publisher,
                               int circulationPercent, int senderBalancePercent, long minAmount) {
        this.stats = stats;
        this.publisher = publisher;
        this.circulationPercent = circulationPercent;
        this.senderBalancePercent = senderBalancePercent;
        this.minAmount = minAmount;
    }

    public void evaluate(AppliedEconomyEvent event) {
        if (event.type() == EconomyEventType.TRANSFER_OUT && event.correlationId() != null) {
            pendingOut.put(event.correlationId().value(), new OutLeg(
                    event.player().value(), event.currency(), event.amount().units(),
                    event.balanceAfter().units(), event.occurredAt().toEpochMilli()));
            return;
        }
        if (event.type() == EconomyEventType.TRANSFER_IN && event.correlationId() != null) {
            OutLeg out = pendingOut.remove(event.correlationId().value());
            if (out != null) {
                evaluateTransfer(out, event.player().value());
            }
            return;
        }
        // Everything else (CREDITED / DEBITED / SET) is a system or admin grant — intentional, not alerted.
    }

    private void evaluateTransfer(OutLeg out, UUID to) {
        if (out.amount() < minAmount) {
            return;
        }
        long circulation = circulation(out.currency());
        long senderBefore = out.senderBalanceAfter() + out.amount(); // outflow: before = after + amount
        List<String> reasons = reasons(out.amount(), circulation, senderBefore);
        if (reasons.isEmpty()) {
            return;
        }
        publisher.publish(new EconomyAlert(out.from(), to, out.currency().value(), "TRANSFER",
                out.amount(), out.senderBalanceAfter(), circulation, String.join(", ", reasons), out.occurredAt()));
    }

    /** Reasons that fired. {@code senderBefore < 0} disables the sender-balance criterion (inflows). */
    private List<String> reasons(long amount, long circulation, long senderBefore) {
        List<String> reasons = new ArrayList<>(2);
        if (circulation > 0 && amount > circulationPercent / 100.0 * circulation) {
            reasons.add(Math.round(amount * 100.0 / circulation) + "% des Umlaufs");
        }
        if (senderBefore > 0 && amount > senderBalancePercent / 100.0 * senderBefore) {
            reasons.add(Math.round(amount * 100.0 / senderBefore) + "% des Sender-Guthabens");
        }
        return reasons;
    }

    private long circulation(CurrencyCode currency) {
        return stats.get(currency).map(CirculationStats::total).orElse(0L);
    }
}
