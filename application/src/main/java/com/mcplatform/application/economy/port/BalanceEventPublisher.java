package com.mcplatform.application.economy.port;

import com.mcplatform.domain.economy.AppliedEconomyEvent;

/**
 * Outbound port for broadcasting balance changes (implemented in the composition root, which maps
 * the domain event to the plugin-protocol wire format and publishes it via Redis Pub/Sub).
 */
public interface BalanceEventPublisher {

    void publish(AppliedEconomyEvent event);
}
