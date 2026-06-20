package com.mcplatform.cache;

import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Hot balance cache for active players (PROGRESS.md section 8). Reads may be served optimistically
 * from here; writes ALWAYS go through the backend (Postgres event + projection) first and only then
 * update this cache — this class never mutates a balance on its own authority. Stored as a Redis
 * HASH with fields {@code balance} and {@code version} ({@code version} = sequence_no of the last
 * applied economy_event, for staleness checks against out-of-order Pub/Sub events).
 */
public final class BalanceCache {

    private static final String F_BALANCE = "balance";
    private static final String F_VERSION = "version";

    private final RedisCacheAdapter redis;

    public BalanceCache(RedisCacheAdapter redis) {
        this.redis = redis;
    }

    /**
     * Warmup / update the cached balance. {@code ttl} {@code null} = no expiry (active player);
     * pass a TTL when a player leaves so the key ages out.
     */
    public void put(UUID player, String currency, long balance, long version, Duration ttl) {
        String key = RedisKeys.balance(player, currency);
        RedisCommands<String, String> c = redis.commands();
        c.hset(key, Map.of(F_BALANCE, Long.toString(balance), F_VERSION, Long.toString(version)));
        if (ttl != null) {
            c.expire(key, ttl);
        }
    }

    public OptionalLong balance(UUID player, String currency) {
        return readLong(player, currency, F_BALANCE);
    }

    public OptionalLong version(UUID player, String currency) {
        return readLong(player, currency, F_VERSION);
    }

    /** Cache invalidation (e.g. on a Pub/Sub event for a player not active on this node). */
    public void evict(UUID player, String currency) {
        redis.commands().del(RedisKeys.balance(player, currency));
    }

    private OptionalLong readLong(UUID player, String currency, String field) {
        String v = redis.commands().hget(RedisKeys.balance(player, currency), field);
        return v == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(v));
    }
}
