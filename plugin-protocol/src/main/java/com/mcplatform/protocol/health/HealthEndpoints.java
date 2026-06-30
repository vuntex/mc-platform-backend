package com.mcplatform.protocol.health;

import com.mcplatform.protocol.core.EndpointDescriptor;
import com.mcplatform.protocol.core.HttpMethod;

/**
 * Health REST endpoint as a constant, so the plugin references it by name (no hard-coded URL). The
 * plugin polls {@link #CHECK} periodically; a non-2xx (e.g. 404 when the backend is gone), a transport
 * failure, or a {@code status != "UP"} body is treated as unhealthy.
 */
public final class HealthEndpoints {

    /** GET backend health. Always idempotent → safe to poll and auto-retry. */
    public static final EndpointDescriptor<Void, HealthResponse> CHECK =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/health", Void.class, HealthResponse.class);

    private HealthEndpoints() {
    }
}
