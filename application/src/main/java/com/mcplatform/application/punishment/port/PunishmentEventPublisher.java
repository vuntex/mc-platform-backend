package com.mcplatform.application.punishment.port;

import com.mcplatform.domain.punishment.AppliedPunishmentEvent;

/**
 * Outbound port for broadcasting punishment changes (implemented in the composition root, which maps
 * the domain event to the plugin-protocol wire format and publishes it on Redis Pub/Sub).
 */
public interface PunishmentEventPublisher {

    void publish(AppliedPunishmentEvent event);
}
