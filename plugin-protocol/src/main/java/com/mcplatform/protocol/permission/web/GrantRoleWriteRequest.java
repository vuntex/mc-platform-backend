package com.mcplatform.protocol.permission.web;

/**
 * Grant a role to a player (web interface). {@code expiresInSeconds == null} means permanent; otherwise
 * the backend computes {@code expires_at = now + expiresInSeconds}. {@code reason} is optional. No
 * {@code actor} field — {@code issued_by} comes from the JWT (FR-020), never the body.
 */
public record GrantRoleWriteRequest(long roleId, Long expiresInSeconds, String reason) {
}
