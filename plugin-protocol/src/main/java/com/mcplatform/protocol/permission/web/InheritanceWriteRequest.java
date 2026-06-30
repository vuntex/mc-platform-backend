package com.mcplatform.protocol.permission.web;

/**
 * Add an inheritance edge from the web interface: the role in the path inherits the permissions of
 * {@code parentRoleId}. Carries NO {@code actor} — the acting admin is derived exclusively from the JWT
 * (FR-018/FR-019), never from the body.
 */
public record InheritanceWriteRequest(long parentRoleId) {
}
