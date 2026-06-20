package com.mcplatform.bootstrap.config;

import com.mcplatform.cache.BalanceCache;
import com.mcplatform.cache.RedisCacheAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the cache layer: adapts the framework-free {@link RedisCacheAdapter}
 * (from infra-cache) into a Spring-managed singleton. This is the only place Spring meets Lettuce.
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class CacheConfig {

    @Bean(destroyMethod = "close")
    RedisCacheAdapter redisCacheAdapter(RedisProperties props) {
        return new RedisCacheAdapter(props.host(), props.port(), props.password());
    }

    @Bean
    BalanceCache balanceCache(RedisCacheAdapter adapter) {
        return new BalanceCache(adapter);
    }
}
