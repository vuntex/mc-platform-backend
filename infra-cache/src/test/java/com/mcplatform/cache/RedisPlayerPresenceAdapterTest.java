package com.mcplatform.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.domain.player.PlayerId;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies the Redis presence set (mark online/offline, membership, listing, count) against a real Redis. */
class RedisPlayerPresenceAdapterTest {

    static GenericContainer<?> redis;
    static RedisCacheAdapter cache;
    static RedisPlayerPresenceAdapter presence;

    @BeforeAll
    static void start() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        cache = new RedisCacheAdapter(redis.getHost(), redis.getMappedPort(6379), null);
        presence = new RedisPlayerPresenceAdapter(cache);
    }

    @AfterAll
    static void stop() {
        if (cache != null) {
            cache.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void marksOnlineOfflineAndReflectsMembershipListingAndCount() {
        PlayerId a = PlayerId.of(UUID.randomUUID());
        PlayerId b = PlayerId.of(UUID.randomUUID());

        long before = presence.onlineCount();
        assertThat(presence.isOnline(a)).isFalse();

        presence.markOnline(a);
        presence.markOnline(b);
        presence.markOnline(a); // idempotent

        assertThat(presence.isOnline(a)).isTrue();
        assertThat(presence.onlinePlayers()).contains(a.value(), b.value());
        assertThat(presence.onlineCount()).isEqualTo(before + 2);

        presence.markOffline(a);
        assertThat(presence.isOnline(a)).isFalse();
        assertThat(presence.onlinePlayers()).contains(b.value()).doesNotContain(a.value());
        assertThat(presence.onlineCount()).isEqualTo(before + 1);

        presence.markOffline(b); // clean up so a re-run starts fresh-ish
    }

    @Test
    void offlineForUnknownPlayerIsNoOp() {
        PlayerId ghost = PlayerId.of(UUID.randomUUID());
        presence.markOffline(ghost); // must not throw
        assertThat(presence.isOnline(ghost)).isFalse();
    }
}
