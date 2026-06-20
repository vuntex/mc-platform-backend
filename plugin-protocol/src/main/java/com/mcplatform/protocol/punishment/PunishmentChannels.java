package com.mcplatform.protocol.punishment;

import com.mcplatform.protocol.core.Channels;

/** Redis Pub/Sub channel names for the punishment feature, built on the shared {@link Channels} convention. */
public final class PunishmentChannels {

    /**
     * Punishment-change events (issue/revoke) for live updates and enforcement across nodes/servers.
     * Payload is a {@link PunishmentChangedEvent} carried in a
     * {@link com.mcplatform.protocol.core.MessageEnvelope} (see {@link PunishmentChangedEventCodec}).
     * Resolves to {@code mc:punishment:changed}.
     */
    public static final String CHANGED = Channels.of("punishment", "changed");

    private PunishmentChannels() {}
}
