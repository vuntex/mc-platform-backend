package com.mcplatform.bootstrap.config;

import com.mcplatform.application.punishment.PunishmentService;
import com.mcplatform.application.punishment.port.PunishmentEventPublisher;
import com.mcplatform.application.punishment.port.PunishmentEventStore;
import com.mcplatform.application.punishment.port.PunishmentTemplateRepository;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.bootstrap.adapter.RedisPunishmentEventPublisher;
import com.mcplatform.cache.RedisCacheAdapter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for the punishment use case: binds the publisher port, the clock and the service. */
@Configuration
public class PunishmentConfig {

    /** A UTC clock so the service computes expiry/active deterministically; injectable for tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PunishmentEventPublisher punishmentEventPublisher(RedisCacheAdapter redis) {
        return new RedisPunishmentEventPublisher(redis);
    }

    @Bean
    PunishmentService punishmentService(PunishmentEventStore store, PunishmentTemplateRepository templates,
            PermissionResolver permissions, PunishmentEventPublisher publisher, Clock clock) {
        return new PunishmentService(store, templates, permissions, publisher, clock);
    }
}
