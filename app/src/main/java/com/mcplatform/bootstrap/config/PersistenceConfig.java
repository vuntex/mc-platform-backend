package com.mcplatform.bootstrap.config;

import com.mcplatform.application.economy.port.CurrencyRepository;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.persistence.JooqCurrencyRepository;
import com.mcplatform.persistence.JooqEconomyRepository;
import com.mcplatform.persistence.JooqPlayerRepository;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for persistence: builds a jOOQ {@link DSLContext} over the Spring-managed
 * {@link DataSource} and wires the framework-free jOOQ repositories as the application's ports.
 */
@Configuration
public class PersistenceConfig {

    @Bean
    DSLContext dslContext(DataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Bean
    EconomyEventStore economyEventStore(DSLContext dsl) {
        return new JooqEconomyRepository(dsl);
    }

    @Bean
    PlayerRepository playerRepository(DSLContext dsl) {
        return new JooqPlayerRepository(dsl);
    }

    @Bean
    CurrencyRepository currencyRepository(DSLContext dsl) {
        return new JooqCurrencyRepository(dsl);
    }
}
