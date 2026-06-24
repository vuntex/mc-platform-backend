package com.mcplatform.domain.permission;

/**
 * What kind of change to a player's effective permissions occurred — the trigger for a live-update event
 * (FR-020/FR-021). A DOMAIN type so the application layer stays {@code plugin-protocol}-free (mirrors
 * how {@code ReportChange} keeps the report use case decoupled from the wire). The composition-root
 * publisher maps {@link #name()} onto the protocol {@code PermissionChangedEvent.changeType} string.
 */
public enum PermissionChangeType {
    GRANT_ADDED,
    GRANT_REVOKED,
    GRANT_EXPIRED,
    ROLE_CONFIG_CHANGED
}
