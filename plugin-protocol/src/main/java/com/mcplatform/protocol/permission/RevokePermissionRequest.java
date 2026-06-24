package com.mcplatform.protocol.permission;

import java.util.UUID;

/** Revoke a player's direct permission grant. {@code actor} is the staff UUID. */
public record RevokePermissionRequest(String permission, String reason, UUID actor) {
}
