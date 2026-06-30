package com.mcplatform.application.economy;

import com.mcplatform.application.economy.port.CirculationStats;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.domain.economy.CurrencyCode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Caches the total money in circulation per currency. Computing the SUM over all balances is too heavy
 * to run per request, so a scheduled task calls {@link #refresh()} periodically and reads serve from
 * the cached snapshot. No framework dependency (wired as a bean in the app module).
 */
public final class EconomyStatsService {

    private final EconomyEventStore store;
    private volatile Map<String, CirculationStats> byCurrency = Map.of();

    public EconomyStatsService(EconomyEventStore store) {
        this.store = store;
    }

    /** Recompute the circulation snapshot from the store (called periodically). */
    public void refresh() {
        Map<String, CirculationStats> snapshot = new LinkedHashMap<>();
        for (CirculationStats stats : store.circulation()) {
            snapshot.put(stats.currency().value(), stats);
        }
        this.byCurrency = Map.copyOf(snapshot);
    }

    public Optional<CirculationStats> get(CurrencyCode currency) {
        return Optional.ofNullable(byCurrency.get(currency.value()));
    }

    public Collection<CirculationStats> all() {
        return byCurrency.values();
    }
}
