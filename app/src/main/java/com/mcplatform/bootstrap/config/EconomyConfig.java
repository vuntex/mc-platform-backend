package com.mcplatform.bootstrap.config;

import com.mcplatform.application.economy.BalanceStreamBroadcaster;
import com.mcplatform.application.economy.EconomyAlertMonitor;
import com.mcplatform.application.economy.EconomyHistoryService;
import com.mcplatform.application.economy.EconomyService;
import com.mcplatform.application.economy.EconomyStatsService;
import com.mcplatform.application.economy.PlayerBalancesQuery;
import com.mcplatform.application.economy.ServerHistoryQuery;
import com.mcplatform.application.economy.TransactionDetailQuery;
import com.mcplatform.application.economy.port.BalanceCachePort;
import com.mcplatform.application.economy.port.BalanceEventPublisher;
import com.mcplatform.application.economy.port.EconomyAlertPublisher;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.bootstrap.adapter.AlertingBalanceEventPublisher;
import com.mcplatform.bootstrap.adapter.RedisBalanceEventPublisher;
import com.mcplatform.bootstrap.adapter.RedisEconomyAlertPublisher;
import com.mcplatform.bootstrap.adapter.RedisEconomyStreamBridge;
import com.mcplatform.cache.BalanceCache;
import com.mcplatform.cache.RedisBalanceCacheAdapter;
import com.mcplatform.cache.RedisCacheAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for the economy use case: binds the cache + publisher ports, the service, and the
 * circulation tracking + suspicious-amount alert monitor. */
@Configuration
public class EconomyConfig {

    @Bean
    BalanceCachePort balanceCachePort(BalanceCache cache) {
        return new RedisBalanceCacheAdapter(cache);
    }

    @Bean
    EconomyStatsService economyStatsService(EconomyReadStore store) {
        return new EconomyStatsService(store);
    }

    @Bean
    EconomyAlertPublisher economyAlertPublisher(RedisCacheAdapter redis) {
        return new RedisEconomyAlertPublisher(redis);
    }

    @Bean
    EconomyAlertMonitor economyAlertMonitor(
            EconomyStatsService stats, EconomyAlertPublisher publisher,
            @Value("${mcplatform.economy.alert.circulation-percent:5}") int circulationPercent,
            @Value("${mcplatform.economy.alert.sender-balance-percent:80}") int senderBalancePercent,
            @Value("${mcplatform.economy.alert.min-amount:1000}") long minAmount) {
        return new EconomyAlertMonitor(stats, publisher, circulationPercent, senderBalancePercent, minAmount);
    }

    @Bean
    BalanceEventPublisher balanceEventPublisher(RedisCacheAdapter redis, EconomyAlertMonitor monitor) {
        // Decorate the real publisher so every applied event is also run through the alert monitor —
        // no change to the core EconomyService.
        return new AlertingBalanceEventPublisher(new RedisBalanceEventPublisher(redis), monitor);
    }

    @Bean
    EconomyService economyService(EconomyEventStore store, BalanceCachePort cache, BalanceEventPublisher publisher) {
        return new EconomyService(store, cache, publisher);
    }

    @Bean
    EconomyHistoryService economyHistoryService(EconomyReadStore store) {
        return new EconomyHistoryService(store);
    }

    @Bean
    PlayerBalancesQuery playerBalancesQuery(EconomyReadStore store) {
        return new PlayerBalancesQuery(store);
    }

    @Bean
    ServerHistoryQuery serverHistoryQuery(EconomyReadStore store) {
        return new ServerHistoryQuery(store);
    }

    @Bean
    TransactionDetailQuery transactionDetailQuery(EconomyReadStore store) {
        return new TransactionDetailQuery(store);
    }

    @Bean
    RedisEconomyStreamBridge redisEconomyStreamBridge(RedisCacheAdapter redis, BalanceStreamBroadcaster broadcaster) {
        return new RedisEconomyStreamBridge(redis, broadcaster);
    }
}
