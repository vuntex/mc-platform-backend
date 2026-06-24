package com.mcplatform.protocol.permission.web;

/**
 * Add/remove a permission on a role's configuration (web interface). No {@code actor} field — the acting
 * admin comes from the JWT (FR-002).
 */
public record RolePermissionWriteRequest(String permission) {
}
