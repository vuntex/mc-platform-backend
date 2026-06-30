package com.mcplatform.protocol.health;

import java.util.Map;

/**
 * Backend liveness/readiness signal for the plugin's health monitor. {@code status} is {@code "UP"} when
 * the backend can serve consistent state; anything else (or an unreachable backend → transport error) is
 * treated as unhealthy by the plugin. {@code components} maps each checked dependency (e.g. {@code "db"},
 * {@code "redis"}) to its own {@code "UP"}/{@code "DOWN"} status, so staff can be told WHAT is down.
 *
 * <p>The endpoint always answers 200 with this body (even when {@code status == "DOWN"}) so the detail
 * reaches the plugin instead of being lost behind a 5xx error. Pure data (JDK only); field names are the
 * wire contract.
 */
public record HealthResponse(String status, Map<String, String> components) {

    public HealthResponse {
        components = components == null ? Map.of() : Map.copyOf(components);
    }
}
