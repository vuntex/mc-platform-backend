package com.mcplatform.protocol.permission;

import java.util.Objects;
import java.util.UUID;

/**
 * Pub/Sub event published whenever a player's effective permissions change — the shared contract between
 * backend and plugin for live, relog-free effect (FR-020/FR-021). Per affected player; on a role-config
 * change the backend publishes one event per current holder. Pure data (JDK only); {@code changeType}
 * is a String mirroring the domain {@code PermissionChangeType}. Wire format lives in
 * {@link PermissionChangedEventCodec}.
 */
public record PermissionChangedEvent(
        UUID playerUuid,
        String changeType,            // GRANT_ADDED | GRANT_REVOKED | GRANT_EXPIRED | ROLE_CONFIG_CHANGED
        long timestampEpochMilli) {

    public PermissionChangedEvent {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(changeType, "changeType");
    }
}
