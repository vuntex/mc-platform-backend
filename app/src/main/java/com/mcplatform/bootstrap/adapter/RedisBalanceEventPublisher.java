package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.economy.port.BalanceEventPublisher;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.domain.economy.AppliedEconomyEvent;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import com.mcplatform.protocol.economy.BalanceChangedEvent;
import com.mcplatform.protocol.economy.BalanceChangedEventCodec;
import com.mcplatform.protocol.economy.EconomyChannels;

/**
 * Bridges the domain {@link AppliedEconomyEvent} to the plugin-protocol wire format and publishes it
 * on Redis Pub/Sub. Lives in the composition root because it is the one place that may depend on both
 * plugin-protocol (the codec) and infra-cache (the transport).
 */
public final class RedisBalanceEventPublisher implements BalanceEventPublisher {

    private final RedisCacheAdapter redis;
    private final MessageProtocol protocol = PlatformProtocol.create();

    public RedisBalanceEventPublisher(RedisCacheAdapter redis) {
        this.redis = redis;
    }

    @Override
    public void publish(AppliedEconomyEvent event) {
        BalanceChangedEvent wire = new BalanceChangedEvent(
                event.player().value(),
                event.currency().value(),
                event.type().name(),
                event.amount().units(),
                event.balanceAfter().units(),
                event.version(),
                event.transactionId().value(),
                event.source(),
                event.correlationId() == null ? null : event.correlationId().value(),
                event.occurredAt().toEpochMilli());
        redis.publish(EconomyChannels.BALANCE, protocol.encode(BalanceChangedEventCodec.INSTANCE, wire));
    }
}
