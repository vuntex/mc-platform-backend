package com.mcplatform.application.economy;

/**
 * Backend-authoritative permission keys for the read-only economy web surface (spec 007, FR-021).
 * Checked via the {@code PermissionResolver} port in the REST/SSE controllers; the plugin/web UI gate
 * is only convenience (Constitution §V.12).
 */
public final class EconomyPermissions {

    /** Required to read economy data over the web (balances, server history, transaction detail, SSE). */
    public static final String READ = "permission.economy.read";

    private EconomyPermissions() {
    }
}
