package com.mcplatform.bootstrap.config;

import com.mcplatform.application.report.ReportService;
import com.mcplatform.application.report.port.ReportPublisher;
import com.mcplatform.application.report.port.ReportRepository;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.bootstrap.adapter.RedisReportEventPublisher;
import com.mcplatform.cache.RedisCacheAdapter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the report use case. Reuses the existing {@code Clock} bean and the
 * {@link PermissionResolver}. The publisher bridges report changes onto Redis Pub/Sub
 * ({@code mc:report:changed}) for live team notification (FR-015).
 */
@Configuration
public class ReportConfig {

    @Bean
    ReportPublisher reportPublisher(RedisCacheAdapter redis) {
        return new RedisReportEventPublisher(redis);
    }

    @Bean
    ReportService reportService(ReportRepository reports, PermissionResolver permissions,
            ReportPublisher publisher, Clock clock,
            @Value("${mcplatform.reports.cooldown-seconds:60}") long cooldownSeconds) {
        return new ReportService(reports, permissions, publisher, clock, Duration.ofSeconds(cooldownSeconds));
    }
}
