package com.mcplatform.protocol.permission;

import java.util.UUID;

/**
 * Grant a role to a player. {@code expiresInSeconds == null} means permanent; otherwise the backend
 * computes {@code expires_at = now + expiresInSeconds}. {@code reason} is optional. {@code actor} is the
 * granting staff UUID.
 */
public record GrantRoleRequest(long roleId, Long expiresInSeconds, String reason, UUID actor) {
}
