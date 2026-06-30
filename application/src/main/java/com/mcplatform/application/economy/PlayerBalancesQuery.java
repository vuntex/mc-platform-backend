package com.mcplatform.application.economy;

import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.application.economy.port.ProjectedBalance;
import com.mcplatform.domain.player.PlayerId;
import java.util.List;

/**
 * Read-only use case (spec 007, US1): all of a player's balances across every currency in one call,
 * straight from the read store. No mutation, no cache, no events. An unknown player or one with no
 * balance rows yields an empty list (the REST layer returns it as an empty list, never a 404).
 */
public final class PlayerBalancesQuery {

    private final EconomyReadStore store;

    public PlayerBalancesQuery(EconomyReadStore store) {
        this.store = store;
    }

    public List<ProjectedBalance> balances(PlayerId player) {
        return store.playerBalances(player);
    }
}
