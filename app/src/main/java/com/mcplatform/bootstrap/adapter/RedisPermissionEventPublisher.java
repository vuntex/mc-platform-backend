package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.permission.port.PermissionChangePublisher;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.domain.permission.PermissionChangeType;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import com.mcplatform.protocol.permission.PermissionChangedEvent;
import com.mcplatform.protocol.permission.PermissionChangedEventCodec;
import com.mcplatform.protocol.permission.PermissionChannels;
import java.time.Clock;
import java.util.UUID;

/**
 * Bridges a domain {@link PermissionChangeType} to the plugin-protocol wire format and publishes it on
 * Redis Pub/Sub ({@code mc:permission:changed}). Lives in the composition root because it is the one
 * place that may depend on both plugin-protocol (the codec) and infra-cache (the transport) — the same
 * shape as {@code RedisReportEventPublisher}. The domain enum is mapped to the wire String here, keeping
 * the application layer protocol-free.
 */
public final class RedisPermissionEventPublisher implements PermissionChangePublisher {

    private final RedisCacheAdapter redis;
    private final Clock clock;
    private final MessageProtocol protocol = PlatformProtocol.create();

    public RedisPermissionEventPublisher(RedisCacheAdapter redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void publish(UUID player, PermissionChangeType type) {
        PermissionChangedEvent wire = new PermissionChangedEvent(player, type.name(), clock.millis());
        redis.publish(PermissionChannels.CHANGED, protocol.encode(PermissionChangedEventCodec.INSTANCE, wire));
    }
}
