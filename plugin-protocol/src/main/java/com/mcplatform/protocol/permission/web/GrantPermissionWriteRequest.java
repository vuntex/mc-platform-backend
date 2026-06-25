package com.mcplatform.protocol.permission.web;

/**
 * Grant a single permission directly to a player (web interface). {@code expiresInSeconds == null} means
 * permanent. {@code reason} is optional. No {@code actor} field — {@code issued_by} comes from the JWT
 * (FR-020), never the body.
 */
public record GrantPermissionWriteRequest(String permission, Long expiresInSeconds, String reason) {
}
