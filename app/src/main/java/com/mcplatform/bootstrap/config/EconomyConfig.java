package com.mcplatform.bootstrap.config;

import com.mcplatform.application.economy.EconomyService;
import com.mcplatform.application.economy.port.BalanceCachePort;
import com.mcplatform.application.economy.port.BalanceEventPublisher;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.bootstrap.adapter.RedisBalanceEventPublisher;
import com.mcplatform.cache.BalanceCache;
import com.mcplatform.cache.RedisBalanceCacheAdapter;
import com.mcplatform.cache.RedisCacheAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for the economy use case: binds the cache + publisher ports and the service. */
@Configuration
public class EconomyConfig {

    @Bean
    BalanceCachePort balanceCachePort(BalanceCache cache) {
        return new RedisBalanceCacheAdapter(cache);
    }

    @Bean
    BalanceEventPublisher balanceEventPublisher(RedisCacheAdapter redis) {
        return new RedisBalanceEventPublisher(redis);
    }

    @Bean
    EconomyService economyService(EconomyEventStore store, BalanceCachePort cache, BalanceEventPublisher publisher) {
        return new EconomyService(store, cache, publisher);
    }
}
