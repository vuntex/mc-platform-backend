package com.mcplatform.bootstrap.config;

import com.mcplatform.application.economy.EconomyService;
import com.mcplatform.application.economy.port.CurrencyRepository;
import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.player.PlayerSessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for player/session use cases. */
@Configuration
public class PlayerConfig {

    @Bean
    PlayerSessionService playerSessionService(PlayerRepository players, CurrencyRepository currencies,
            EconomyService economy) {
        return new PlayerSessionService(players, currencies, economy);
    }
}
