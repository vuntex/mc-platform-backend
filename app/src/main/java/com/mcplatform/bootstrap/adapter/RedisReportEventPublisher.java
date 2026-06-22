package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.report.port.ReportPublisher;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.domain.report.ReportChange;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import com.mcplatform.protocol.report.ReportChangedEvent;
import com.mcplatform.protocol.report.ReportChangedEventCodec;
import com.mcplatform.protocol.report.ReportChannels;

/**
 * Bridges the domain {@link ReportChange} to the plugin-protocol wire format and publishes it on Redis
 * Pub/Sub ({@code mc:report:changed}). Lives in the composition root because it is the one place that may
 * depend on both plugin-protocol (the codec) and infra-cache (the transport) — the same shape as
 * {@link RedisPunishmentEventPublisher}. The wire event carries no chat context (FR-015).
 */
public final class RedisReportEventPublisher implements ReportPublisher {

    private final RedisCacheAdapter redis;
    private final MessageProtocol protocol = PlatformProtocol.create();

    public RedisReportEventPublisher(RedisCacheAdapter redis) {
        this.redis = redis;
    }

    @Override
    public void publish(ReportChange change) {
        ReportChangedEvent wire = new ReportChangedEvent(
                change.reportId().value(),
                change.reporter().value(),
                change.target().value(),
                change.category().name(),
                change.status().name(),
                change.changeType().name(),
                change.occurredAt().toEpochMilli());
        redis.publish(ReportChannels.CHANGED, protocol.encode(ReportChangedEventCodec.INSTANCE, wire));
    }
}
