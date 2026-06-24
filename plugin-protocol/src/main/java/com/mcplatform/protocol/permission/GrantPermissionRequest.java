package com.mcplatform.protocol.permission;

import java.util.UUID;

/**
 * Grant a single permission directly to a player. {@code expiresInSeconds == null} means permanent.
 * {@code reason} is optional. {@code actor} is the granting staff UUID.
 */
public record GrantPermissionRequest(String permission, Long expiresInSeconds, String reason, UUID actor) {
}
