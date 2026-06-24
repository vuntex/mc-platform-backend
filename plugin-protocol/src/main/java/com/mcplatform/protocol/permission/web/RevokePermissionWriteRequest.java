package com.mcplatform.protocol.permission.web;

/**
 * Revoke a player's direct permission grant (web interface). {@code reason} is optional. No {@code actor}
 * field — the acting admin comes from the JWT (FR-002).
 */
public record RevokePermissionWriteRequest(String permission, String reason) {
}
