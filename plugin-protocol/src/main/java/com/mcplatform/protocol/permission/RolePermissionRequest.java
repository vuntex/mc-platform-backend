package com.mcplatform.protocol.permission;

import java.util.UUID;

/** Add or remove a single permission on a role's configuration. {@code actor} is the staff UUID. */
public record RolePermissionRequest(String permission, UUID actor) {
}
