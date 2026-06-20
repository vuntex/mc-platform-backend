package com.mcplatform.bootstrap.config;

import com.mcplatform.cache.RedisCacheAdapter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes a {@code redis} entry to {@code /actuator/health} by pinging through the single
 * {@link RedisCacheAdapter} connection — replacing the spring-data-redis health indicator so the
 * backend keeps exactly one Redis connection path.
 */
@Component
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisCacheAdapter cache;

    public RedisHealthIndicator(RedisCacheAdapter cache) {
        this.cache = cache;
    }

    @Override
    public Health health() {
        try {
            String reply = cache.ping();
            if ("PONG".equalsIgnoreCase(reply)) {
                return Health.up().build();
            }
            return Health.down().withDetail("ping", reply).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
