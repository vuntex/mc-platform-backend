package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.punishment.port.PunishmentEventPublisher;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.domain.punishment.AppliedPunishmentEvent;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import com.mcplatform.protocol.punishment.PunishmentChangedEvent;
import com.mcplatform.protocol.punishment.PunishmentChangedEventCodec;
import com.mcplatform.protocol.punishment.PunishmentChannels;

/**
 * Bridges the domain {@link AppliedPunishmentEvent} to the plugin-protocol wire format and publishes it
 * on Redis Pub/Sub ({@code mc:punishment:changed}). Lives in the composition root because it is the one
 * place that may depend on both plugin-protocol (the codec) and infra-cache (the transport) — the same
 * shape as {@link RedisBalanceEventPublisher}.
 */
public final class RedisPunishmentEventPublisher implements PunishmentEventPublisher {

    private final RedisCacheAdapter redis;
    private final MessageProtocol protocol = PlatformProtocol.create();

    public RedisPunishmentEventPublisher(RedisCacheAdapter redis) {
        this.redis = redis;
    }

    @Override
    public void publish(AppliedPunishmentEvent event) {
        PunishmentChangedEvent wire = new PunishmentChangedEvent(
                event.punishmentId().value(),
                event.player().value(),
                event.type().name(),
                event.action().name(),
                event.reason(),
                event.actor().value(),
                event.expiresAt() == null ? 0L : event.expiresAt().toEpochMilli(),
                event.version(),
                event.occurredAt().toEpochMilli());
        redis.publish(PunishmentChannels.CHANGED, protocol.encode(PunishmentChangedEventCodec.INSTANCE, wire));
    }
}
