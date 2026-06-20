package com.mcplatform.protocol;

import java.util.UUID;

/**
 * Shared DTO for plugin &lt;-&gt; backend balance messages. Pure data, no framework
 * dependencies — this module is consumed by the separate plugin repo.
 */
public record BalanceMessage(UUID playerUuid, String currencyCode, long balance) {
}
