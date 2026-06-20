package com.mcplatform.cache;

import com.mcplatform.application.economy.port.BalanceCachePort;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.player.PlayerId;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Adapts the framework-free {@link BalanceCache} to the application's {@link BalanceCachePort},
 * mapping between domain types and the raw HASH storage.
 */
public final class RedisBalanceCacheAdapter implements BalanceCachePort {

    private final BalanceCache cache;

    public RedisBalanceCacheAdapter(BalanceCache cache) {
        this.cache = cache;
    }

    @Override
    public Optional<Balance> find(PlayerId player, CurrencyCode currency) {
        OptionalLong balance = cache.balance(player.value(), currency.value());
        OptionalLong version = cache.version(player.value(), currency.value());
        if (balance.isEmpty() || version.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Balance(player, currency, Money.of(balance.getAsLong()), version.getAsLong()));
    }

    @Override
    public void update(Balance balance) {
        // TTL null = active player; a TTL is applied later on player leave.
        cache.put(balance.player().value(), balance.currency().value(),
                balance.amount().units(), balance.version(), null);
    }

    @Override
    public void evict(PlayerId player, CurrencyCode currency) {
        cache.evict(player.value(), currency.value());
    }
}
