package com.mcplatform.protocol.webauth;

/**
 * Slim view of the caller's primary (display) rank for the web UI — the highest-priority active role
 * (teamRank → weight → id), or the default role when none applies. Display data only ({@code name},
 * {@code displayName}, {@code color}, {@code weight}); never roles/permissions. Part of the
 * {@link MeResponse} contract.
 */
public record PrimaryRole(String name, String displayName, String color, int weight) {
}
