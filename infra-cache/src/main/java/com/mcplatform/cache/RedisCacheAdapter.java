package com.mcplatform.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Redis adapter built directly on Lettuce — the single Redis connection path for the backend.
 * No Spring in this module (PROGRESS.md section 5); the composition root (:app) wires it as a
 * bean and exposes the health check.
 *
 * <p>The {@link RedisClient} is created without any I/O, so constructing this adapter never fails
 * even if Redis is down. The actual connection is opened lazily on first use and transparently
 * re-established by Lettuce after a drop — so the app starts regardless of Redis availability and
 * the health check reflects the live state.
 */
public final class RedisCacheAdapter implements AutoCloseable {

    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisCacheAdapter(String host, int port, String password) {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofSeconds(2));
        if (password != null && !password.isBlank()) {
            uri.withPassword(password.toCharArray());
        }
        this.client = RedisClient.create(uri.build());
    }

    /** Liveness probe used by the health endpoint. Returns the raw reply (expected {@code "PONG"}). */
    public String ping() {
        return commands().ping();
    }

    /** Synchronous command API over the shared connection. Entry point for future caching use cases. */
    public RedisCommands<String, String> commands() {
        return connection().sync();
    }

    /** Publish a raw message to a Pub/Sub channel. (De)serialization is the caller's concern. */
    public void publish(String channel, String message) {
        commands().publish(channel, message);
    }

    /**
     * Subscribe to a channel on a dedicated Pub/Sub connection. The returned handle unsubscribes
     * and frees the connection when closed. Messages are delivered on Lettuce's listener thread.
     */
    public AutoCloseable subscribe(String channel, Consumer<String> onMessage) {
        StatefulRedisPubSubConnection<String, String> pubSub = client.connectPubSub();
        pubSub.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String ch, String msg) {
                if (channel.equals(ch)) {
                    onMessage.accept(msg);
                }
            }
        });
        pubSub.sync().subscribe(channel);
        return pubSub;
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> local = connection;
        if (local == null || !local.isOpen()) {
            synchronized (this) {
                if (connection == null || !connection.isOpen()) {
                    connection = client.connect();
                }
                local = connection;
            }
        }
        return local;
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown();
    }
}
