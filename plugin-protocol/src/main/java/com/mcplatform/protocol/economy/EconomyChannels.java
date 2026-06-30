package com.mcplatform.protocol.economy;

import com.mcplatform.protocol.core.Channels;

/** Redis Pub/Sub channel names for the economy feature, built on the shared {@link Channels} convention. */
public final class EconomyChannels {

    /**
     * Balance-change events for live updates and cross-node/-server cache invalidation.
     * Payload is a {@link BalanceChangedEvent} carried in a
     * {@link com.mcplatform.protocol.core.MessageEnvelope} (see {@link BalanceChangedEventCodec}).
     */
    public static final String BALANCE = Channels.of("economy", "balance");

    /**
     * Economy alert events (suspiciously high amounts) for admin broadcast + server logging. Payload is
     * an {@link EconomyAlertEvent} (see {@link EconomyAlertEventCodec}).
     */
    public static final String ALERT = Channels.of("economy", "alert");

    private EconomyChannels() {}
}
