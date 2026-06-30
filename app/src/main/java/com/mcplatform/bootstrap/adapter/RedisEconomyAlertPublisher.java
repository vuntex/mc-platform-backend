package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.economy.EconomyAlert;
import com.mcplatform.application.economy.port.EconomyAlertPublisher;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import com.mcplatform.protocol.economy.EconomyAlertEvent;
import com.mcplatform.protocol.economy.EconomyAlertEventCodec;
import com.mcplatform.protocol.economy.EconomyChannels;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Publishes economy alerts to Redis ({@code mc:economy:alert}) for the plugin to broadcast, and logs them
 * server-side (the "loggen" half). Mirrors {@code RedisBalanceEventPublisher}.
 */
public final class RedisEconomyAlertPublisher implements EconomyAlertPublisher {

    private static final Logger LOG = System.getLogger(RedisEconomyAlertPublisher.class.getName());

    private final RedisCacheAdapter redis;
    private final MessageProtocol protocol = PlatformProtocol.create();

    public RedisEconomyAlertPublisher(RedisCacheAdapter redis) {
        this.redis = redis;
    }

    @Override
    public void publish(EconomyAlert alert) {
        LOG.log(Level.WARNING, "ECONOMY ALERT: " + alert.type() + " amount=" + alert.amount()
                + " " + alert.currency() + " from=" + alert.player()
                + (alert.target() == null ? "" : " to=" + alert.target())
                + " (" + alert.reason() + "), circulation=" + alert.circulation());
        EconomyAlertEvent wire = new EconomyAlertEvent(
                alert.player(), alert.target(), alert.currency(), alert.type(),
                alert.amount(), alert.balanceAfter(), alert.circulation(),
                alert.reason(), alert.timestampEpochMilli());
        redis.publish(EconomyChannels.ALERT, protocol.encode(EconomyAlertEventCodec.INSTANCE, wire));
    }
}
