package com.mcplatform.bootstrap.web;

import com.mcplatform.bootstrap.config.RedisHealthIndicator;
import com.mcplatform.protocol.health.HealthResponse;

import org.springframework.boot.actuate.health.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deep readiness endpoint the plugin's health monitor polls (GET {@code /api/health}). It reports the
 * status of each dependency it can serve consistent state through — the database AND Redis — plus an
 * overall {@code UP}/{@code DOWN}. Overall is {@code UP} only when ALL checked components are up.
 *
 * <p>Always answers 200 with the body (even when DOWN) so the plugin gets the per-component detail (to
 * tell staff WHAT is down) instead of losing it behind a 5xx. Liveness is implicit: if the backend
 * process is gone the plugin gets a transport error / 404 and locks down anyway.
 *
 * <p>Lives in {@code :app} (not {@code :api-rest}) because it needs the DB {@link DataSource} and the
 * {@link RedisHealthIndicator}, which are wired here. Checks DB + Redis specifically (not the actuator
 * aggregate) so unrelated indicators like {@code diskSpace} can't cause a false lockdown. {@code permitAll}.
 */
@RestController
public class HealthController {

    /** Seconds the JDBC driver may take to validate the connection before we treat the DB as down. */
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 1;

    private final DataSource dataSource;
    private final RedisHealthIndicator redisHealth;

    public HealthController(DataSource dataSource, RedisHealthIndicator redisHealth) {
        this.dataSource = dataSource;
        this.redisHealth = redisHealth;
    }

    @GetMapping("/api/health")
    public HealthResponse health() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("db", databaseUp() ? "UP" : "DOWN");
        components.put("redis", redisUp() ? "UP" : "DOWN");
        boolean up = components.values().stream().allMatch("UP"::equals);
        return new HealthResponse(up ? "UP" : "DOWN", components);
    }

    private boolean databaseUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean redisUp() {
        try {
            return Status.UP.equals(redisHealth.health().getStatus());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
