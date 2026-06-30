package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.economy.BalanceStreamBroadcaster;
import com.mcplatform.application.economy.BalanceStreamView;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import com.mcplatform.protocol.economy.BalanceChangedEvent;
import com.mcplatform.protocol.economy.EconomyChannels;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges the existing {@code mc:economy:balance} Redis pub/sub to the web SSE fan-out (spec 007, US4).
 * Mirrors {@code RedisBalanceEventPublisher}: lives in {@code :app} because only the composition root
 * may see both {@code infra-cache} and {@code plugin-protocol}. Subscribes ONCE at startup, decodes
 * each wire message via the SAME {@link PlatformProtocol#create()} the publisher/plugin use, and hands
 * a neutral {@link BalanceStreamView} to the {@link BalanceStreamBroadcaster}. No new event or codec.
 *
 * <p>Redis-outage behaviour: if the subscribe fails at startup (Redis down) the app still boots — the
 * stream simply delivers nothing until the next restart with Redis present (no auto-reconnect in this
 * single-server slice). A decode/dispatch error never breaks the Lettuce listener thread.
 */
public final class RedisEconomyStreamBridge {

    private static final Logger LOG = Logger.getLogger(RedisEconomyStreamBridge.class.getName());

    private final RedisCacheAdapter redis;
    private final BalanceStreamBroadcaster broadcaster;
    private final MessageProtocol protocol = PlatformProtocol.create();
    private AutoCloseable subscription;

    public RedisEconomyStreamBridge(RedisCacheAdapter redis, BalanceStreamBroadcaster broadcaster) {
        this.redis = redis;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    public void start() {
        try {
            subscription = redis.subscribe(EconomyChannels.BALANCE, this::onMessage);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "economy balance stream subscribe failed (Redis down?); live push disabled", e);
        }
    }

    private void onMessage(String wire) {
        try {
            if (protocol.decode(wire) instanceof BalanceChangedEvent e) {
                broadcaster.broadcast(new BalanceStreamView(
                        e.playerUuid(), e.currencyCode(), e.eventType(), e.amount(), e.balance(),
                        e.version(), e.transactionId(), e.source(), e.correlationId(), e.timestampEpochMilli()));
            }
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "failed to dispatch balance stream event", ex);
        }
    }

    @PreDestroy
    public void stop() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // shutting down; nothing to recover
            }
        }
    }
}
