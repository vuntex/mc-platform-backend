package com.mcplatform.cache;

import java.util.UUID;

/**
 * Redis key schema for the hot cache (backend-internal — the plugin never reads these directly,
 * it always goes through the backend). Pub/Sub channel names are the shared contract and live in
 * {@code plugin-protocol} instead.
 */
public final class RedisKeys {

    private static final String BALANCE_PREFIX = "mc:bal:";

    private RedisKeys() {}

    /** Hot balance cache key: a HASH with fields {@code balance} and {@code version}. */
    public static String balance(UUID playerUuid, String currencyCode) {
        return BALANCE_PREFIX + playerUuid + ":" + currencyCode;
    }
}
