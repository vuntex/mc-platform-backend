package com.mcplatform.bootstrap.config;

import com.mcplatform.application.economy.EconomyService;
import com.mcplatform.application.economy.port.CurrencyRepository;
import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.player.PlayerDirectoryService;
import com.mcplatform.application.player.PlayerSessionService;
import com.mcplatform.application.player.port.PlayerPresencePort;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.cache.RedisPlayerPresenceAdapter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for player/session use cases. */
@Configuration
public class PlayerConfig {

    @Bean
    PlayerPresencePort playerPresencePort(RedisCacheAdapter redis) {
        return new RedisPlayerPresenceAdapter(redis);
    }

    @Bean
    PlayerSessionService playerSessionService(PlayerRepository players, CurrencyRepository currencies,
            EconomyService economy, PlayerPresencePort presence) {
        return new PlayerSessionService(players, currencies, economy, presence);
    }

    @Bean
    PlayerDirectoryService playerDirectoryService(PlayerRepository players, PlayerPresencePort presence,
            Clock clock) {
        return new PlayerDirectoryService(players, presence, clock);
    }
}
