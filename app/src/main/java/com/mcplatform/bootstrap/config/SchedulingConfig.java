package com.mcplatform.bootstrap.config;

import com.mcplatform.application.permission.GrantExpiryService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Enables backend scheduling and drives the permission live-expiry sweep (FR-020). The first scheduler
 * in the backend — a deliberate composition-root addition (documented in the plan's pattern-leak
 * ledger); the sweep logic itself is framework-free in {@link GrantExpiryService}. Default interval is
 * 30 s (≤ 60 s target, SC-004), overridable via {@code mcplatform.permission.expiry-sweep-millis}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    private final GrantExpiryService expiry;

    public SchedulingConfig(GrantExpiryService expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${mcplatform.permission.expiry-sweep-millis:30000}")
    public void sweepExpiredGrants() {
        expiry.sweep();
    }
}
