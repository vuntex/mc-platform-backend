package com.mcplatform.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies the Lettuce adapter against a real Redis: ping, balance cache, and Pub/Sub roundtrip. */
class RedisCacheIntegrationTest {

    static GenericContainer<?> redis;
    static RedisCacheAdapter adapter;

    @BeforeAll
    static void start() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        adapter = new RedisCacheAdapter(redis.getHost(), redis.getMappedPort(6379), null);
    }

    @AfterAll
    static void stop() {
        if (adapter != null) {
            adapter.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void pingReachesRedis() {
        assertThat(adapter.ping()).isEqualToIgnoringCase("PONG");
    }

    @Test
    void balanceCacheStoresReadsAndEvicts() {
        BalanceCache cache = new BalanceCache(adapter);
        UUID player = UUID.randomUUID();

        cache.put(player, "COINS", 1234L, 7L, Duration.ofMinutes(5));
        assertThat(cache.balance(player, "COINS")).hasValue(1234L);
        assertThat(cache.version(player, "COINS")).hasValue(7L);

        cache.evict(player, "COINS");
        assertThat(cache.balance(player, "COINS")).isEmpty();
    }

    @Test
    void pubSubDeliversPublishedMessages() throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        try (AutoCloseable ignored = adapter.subscribe("test:channel", received::offer)) {
            Thread.sleep(200); // let the subscription register before publishing
            adapter.publish("test:channel", "hello");
            assertThat(received.poll(3, TimeUnit.SECONDS)).isEqualTo("hello");
        }
    }
}
