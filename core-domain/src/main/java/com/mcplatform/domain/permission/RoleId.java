package com.mcplatform.domain.permission;

/**
 * Identity of a {@link Role}. A flat, server-independent role (no inheritance). The numeric value maps
 * to the {@code role.id} BIGSERIAL; it is also the stable tie-break key for display selection (FR-019).
 */
public record RoleId(long value) {

    public static RoleId of(long value) {
        return new RoleId(value);
    }
}
