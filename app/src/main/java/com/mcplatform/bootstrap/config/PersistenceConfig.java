package com.mcplatform.bootstrap.config;

import com.mcplatform.application.economy.port.CurrencyRepository;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.permission.port.GrantAuditPort;
import com.mcplatform.application.permission.port.RoleAuditPort;
import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleInheritanceRepository;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.application.punishment.port.PunishmentEventStore;
import com.mcplatform.application.punishment.port.PunishmentTemplateRepository;
import com.mcplatform.application.report.port.ReportRepository;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.application.webauth.port.LinkTokenRepository;
import com.mcplatform.application.webauth.port.RefreshTokenRepository;
import com.mcplatform.application.webauth.port.WebAccountRepository;
import com.mcplatform.persistence.JooqCurrencyRepository;
import com.mcplatform.persistence.JooqEconomyReadStore;
import com.mcplatform.persistence.JooqEconomyRepository;
import com.mcplatform.persistence.JooqGrantAuditRepository;
import com.mcplatform.persistence.JooqRoleAuditRepository;
import com.mcplatform.persistence.JooqPermissionResolver;
import com.mcplatform.persistence.JooqPlayerGrantRepository;
import com.mcplatform.persistence.JooqPlayerRepository;
import com.mcplatform.persistence.JooqPunishmentRepository;
import com.mcplatform.persistence.JooqPunishmentTemplateRepository;
import com.mcplatform.persistence.JooqLinkTokenRepository;
import com.mcplatform.persistence.JooqRefreshTokenRepository;
import com.mcplatform.persistence.JooqReportRepository;
import com.mcplatform.persistence.JooqRoleInheritanceRepository;
import com.mcplatform.persistence.JooqRoleRepository;
import com.mcplatform.persistence.JooqWebAccountRepository;
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
    EconomyReadStore economyReadStore(DSLContext dsl) {
        return new JooqEconomyReadStore(dsl);
    }

    @Bean
    PlayerRepository playerRepository(DSLContext dsl) {
        return new JooqPlayerRepository(dsl);
    }

    @Bean
    CurrencyRepository currencyRepository(DSLContext dsl) {
        return new JooqCurrencyRepository(dsl);
    }

    @Bean
    PunishmentEventStore punishmentEventStore(DSLContext dsl) {
        return new JooqPunishmentRepository(dsl);
    }

    @Bean
    PunishmentTemplateRepository punishmentTemplateRepository(DSLContext dsl) {
        return new JooqPunishmentTemplateRepository(dsl);
    }

    @Bean
    PermissionResolver permissionResolver(DSLContext dsl) {
        return new JooqPermissionResolver(dsl);
    }

    @Bean
    ReportRepository reportRepository(DSLContext dsl) {
        return new JooqReportRepository(dsl);
    }

    @Bean
    RoleRepository roleRepository(DSLContext dsl) {
        return new JooqRoleRepository(dsl);
    }

    @Bean
    PlayerGrantRepository playerGrantRepository(DSLContext dsl) {
        return new JooqPlayerGrantRepository(dsl);
    }

    @Bean
    RoleInheritanceRepository roleInheritanceRepository(DSLContext dsl) {
        return new JooqRoleInheritanceRepository(dsl);
    }

    @Bean
    GrantAuditPort grantAuditPort(DSLContext dsl) {
        return new JooqGrantAuditRepository(dsl);
    }

    @Bean
    RoleAuditPort roleAuditPort(DSLContext dsl) {
        return new JooqRoleAuditRepository(dsl);
    }

    @Bean
    WebAccountRepository webAccountRepository(DSLContext dsl) {
        return new JooqWebAccountRepository(dsl);
    }

    @Bean
    LinkTokenRepository linkTokenRepository(DSLContext dsl) {
        return new JooqLinkTokenRepository(dsl);
    }

    @Bean
    RefreshTokenRepository refreshTokenRepository(DSLContext dsl) {
        return new JooqRefreshTokenRepository(dsl);
    }
}
