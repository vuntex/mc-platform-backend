package com.mcplatform.application.permission.port;

/** Raised when a role name collides (case-insensitive) with an existing role. Maps to HTTP 409. */
public final class RoleNameConflictException extends RuntimeException {

    public RoleNameConflictException(String name) {
        super("role name already exists: " + name);
    }
}
