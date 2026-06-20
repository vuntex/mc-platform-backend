package com.mcplatform.application.economy;

import com.mcplatform.application.economy.port.AppendResult;
import com.mcplatform.application.economy.port.BalanceCachePort;
import com.mcplatform.application.economy.port.BalanceEventPublisher;
import com.mcplatform.application.economy.port.ConcurrencyConflictException;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.application.economy.port.TransferResult;
import com.mcplatform.domain.economy.AppliedEconomyEvent;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.economy.PendingEconomyEvent;
import com.mcplatform.domain.economy.Transfer;
import com.mcplatform.domain.economy.TransferEvents;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.domain.player.PlayerId;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.function.Function;

/**
 * Application use case for economy operations. Orchestrates the secure write path:
 * idempotency check → load projection → let the domain compute the event(s) (validates funds) →
 * append/transfer with optimistic locking (retrying on conflict) → update the hot cache and publish
 * live-update events.
 *
 * <p>Idempotency is checked FIRST (by transaction/correlation id): a replay returns the recorded
 * result without re-running the domain — otherwise a replayed debit could spuriously fail the funds
 * check against the already-updated balance. Cache update and publish happen AFTER the DB commit and
 * are best-effort: a failure there never fails the operation, since Postgres is the source of truth.
 */
public final class EconomyService {

    private static final Logger LOG = System.getLogger(EconomyService.class.getName());
    private static final int MAX_ATTEMPTS = 5;

    private final EconomyEventStore store;
    private final BalanceCachePort cache;
    private final BalanceEventPublisher publisher;

    public EconomyService(EconomyEventStore store, BalanceCachePort cache, BalanceEventPublisher publisher) {
        this.store = store;
        this.cache = cache;
        this.publisher = publisher;
    }

    public Balance credit(PlayerId player, CurrencyCode currency, Money amount, TransactionId tx, String source) {
        return mutate(player, currency, tx, balance -> balance.credit(amount, tx, source));
    }

    public Balance debit(PlayerId player, CurrencyCode currency, Money amount, TransactionId tx, String source) {
        return mutate(player, currency, tx, balance -> balance.debit(amount, tx, source));
    }

    public Balance set(PlayerId player, CurrencyCode currency, Money target, TransactionId tx, String source) {
        return mutate(player, currency, tx, balance -> balance.set(target, tx, source));
    }

    /**
     * Materialise a consistent zero balance for a currency whose configured default is 0: no event is
     * written (a CREDITED 0 carries no information), but the projection row is created at 0 so the
     * player's balance set is complete. Idempotent.
     */
    public Balance ensureZeroBalance(PlayerId player, CurrencyCode currency) {
        store.ensureZeroBalance(player, currency);
        Balance zero = Balance.initial(player, currency);
        safeCacheUpdate(zero);
        return zero;
    }

    /** Read path: serve from cache if present, otherwise read the projection and warm the cache. */
    public Balance balance(PlayerId player, CurrencyCode currency) {
        Optional<Balance> cached = cache.find(player, currency);
        if (cached.isPresent()) {
            return cached.get();
        }
        Balance fromStore = store.currentBalance(player, currency);
        safeCacheUpdate(fromStore);
        return fromStore;
    }

    /** Atomic transfer between two players in the same currency. */
    public TransferOutcome transfer(PlayerId from, PlayerId to, CurrencyCode currency, Money amount,
            TransferId correlationId, String source) {
        Optional<TransferResult> replay = store.findTransfer(correlationId);
        if (replay.isPresent()) {
            return applyTransferToCache(from, to, currency, replay.get(), false);
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Balance balanceFrom = store.currentBalance(from, currency);
            Balance balanceTo = store.currentBalance(to, currency);
            TransferEvents events = Transfer.prepare(balanceFrom, balanceTo, amount, correlationId, source);
            try {
                TransferResult result = store.transfer(
                        events.out(), balanceFrom.version(), events.in(), balanceTo.version());
                publishLeg(events.out(), result.out());
                publishLeg(events.in(), result.in());
                return applyTransferToCache(from, to, currency, result, true);
            } catch (ConcurrencyConflictException conflict) {
                LOG.log(Level.DEBUG, "transfer conflict (attempt {0}/{1}) for {2}",
                        attempt, MAX_ATTEMPTS, correlationId.value());
            }
        }
        throw new ConcurrencyConflictException("exceeded " + MAX_ATTEMPTS + " retries for transfer " + correlationId.value());
    }

    private Balance mutate(PlayerId player, CurrencyCode currency, TransactionId tx,
            Function<Balance, PendingEconomyEvent> operation) {
        Optional<AppendResult> replay = store.findByTransactionId(tx);
        if (replay.isPresent()) {
            Balance recorded = new Balance(player, currency, replay.get().balanceAfter(), replay.get().version());
            safeCacheUpdate(recorded);
            return recorded;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Balance current = store.currentBalance(player, currency);
            // Domain validates here (e.g. InsufficientFundsException) and propagates — not retried.
            PendingEconomyEvent event = operation.apply(current);
            try {
                AppendResult result = store.append(event, current.version());
                Balance updated = new Balance(player, currency, result.balanceAfter(), result.version());
                safeCacheUpdate(updated);
                publishLeg(event, result);
                return updated;
            } catch (ConcurrencyConflictException conflict) {
                LOG.log(Level.DEBUG, "optimistic-lock conflict (attempt {0}/{1}) for {2}/{3}",
                        attempt, MAX_ATTEMPTS, player.value(), currency.value());
            }
        }
        throw new ConcurrencyConflictException(
                "exceeded " + MAX_ATTEMPTS + " retries for " + player.value() + "/" + currency.value());
    }

    private TransferOutcome applyTransferToCache(PlayerId from, PlayerId to, CurrencyCode currency,
            TransferResult result, boolean fresh) {
        Balance newFrom = new Balance(from, currency, result.out().balanceAfter(), result.out().version());
        Balance newTo = new Balance(to, currency, result.in().balanceAfter(), result.in().version());
        safeCacheUpdate(newFrom);
        safeCacheUpdate(newTo);
        return new TransferOutcome(newFrom, newTo);
    }

    private void publishLeg(PendingEconomyEvent event, AppendResult result) {
        safePublish(new AppliedEconomyEvent(event.player(), event.currency(), event.type(), event.amount(),
                result.balanceAfter(), result.version(), event.transactionId(), event.source(),
                event.correlationId(), result.occurredAt()));
    }

    private void safeCacheUpdate(Balance balance) {
        try {
            cache.update(balance);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cache update failed (non-fatal)", e);
        }
    }

    private void safePublish(AppliedEconomyEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "balance event publish failed (non-fatal)", e);
        }
    }
}
