package com.mcplatform.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis connection settings for the {@code infra-cache} adapter, bound from {@code mcplatform.redis.*}.
 * Sensible defaults so the adapter bean can always be constructed.
 */
@ConfigurationProperties(prefix = "mcplatform.redis")
public record RedisProperties(String host, int port, String password) {

    public RedisProperties {
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        if (port == 0) {
            port = 6379;
        }
    }
}
