package com.mcplatform.protocol.permission;

import com.mcplatform.protocol.core.Channels;

/** Redis Pub/Sub channel names for the permission feature, built on the shared {@link Channels} convention. */
public final class PermissionChannels {

    /**
     * Permission-change events (grant added/revoked/expired, role-config changed) for live, relog-free
     * effect on online players. Payload is a {@link PermissionChangedEvent} in a
     * {@link com.mcplatform.protocol.core.MessageEnvelope}. Resolves to {@code mc:permission:changed}.
     */
    public static final String CHANGED = Channels.of("permission", "changed");

    private PermissionChannels() {}
}
