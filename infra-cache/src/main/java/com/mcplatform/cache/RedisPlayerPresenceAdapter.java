package com.mcplatform.cache;

import com.mcplatform.application.player.port.PlayerPresencePort;
import com.mcplatform.domain.player.PlayerId;
import io.lettuce.core.RedisException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed {@link PlayerPresencePort}: a single SET ({@code mc:presence:online}) of online player
 * UUIDs. The plugin reports join/leave over REST; the backend records it here (Constitution: backend is
 * authoritative, the plugin never touches this key directly).
 *
 * <p>Presence is a soft, ephemeral signal, never a correctness boundary — so every op degrades on a
 * Redis outage: writes are best-effort (swallowed + logged, so a join is never broken by a cache blip),
 * reads return empty/0. This mirrors the app's "starts even if Redis is down" philosophy.
 */
public final class RedisPlayerPresenceAdapter implements PlayerPresencePort {

    /** SET of currently-online player UUIDs (as strings). */
    static final String KEY = "mc:presence:online";

    private static final Logger LOG = System.getLogger(RedisPlayerPresenceAdapter.class.getName());

    private final RedisCacheAdapter redis;

    public RedisPlayerPresenceAdapter(RedisCacheAdapter redis) {
        this.redis = redis;
    }

    @Override
    public void markOnline(PlayerId player) {
        try {
            redis.commands().sadd(KEY, player.value().toString());
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "presence markOnline failed for {0}", player.value(), e);
        }
    }

    @Override
    public void markOffline(PlayerId player) {
        try {
            redis.commands().srem(KEY, player.value().toString());
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "presence markOffline failed for {0}", player.value(), e);
        }
    }

    @Override
    public boolean isOnline(PlayerId player) {
        try {
            return Boolean.TRUE.equals(redis.commands().sismember(KEY, player.value().toString()));
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "presence isOnline failed for {0}", player.value(), e);
            return false;
        }
    }

    @Override
    public Set<UUID> onlinePlayers() {
        try {
            Set<String> raw = redis.commands().smembers(KEY);
            Set<UUID> result = new HashSet<>(raw.size());
            for (String s : raw) {
                try {
                    result.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                    // stray non-UUID member — skip it rather than fail the whole read
                }
            }
            return result;
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "presence onlinePlayers failed", e);
            return Set.of();
        }
    }

    @Override
    public long onlineCount() {
        try {
            return redis.commands().scard(KEY);
        } catch (RedisException e) {
            LOG.log(Level.WARNING, "presence onlineCount failed", e);
            return 0L;
        }
    }
}
