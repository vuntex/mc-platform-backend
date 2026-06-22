package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.RoleId;

/** Raised when a referenced role does not exist. Maps to HTTP 404. */
public final class RoleNotFoundException extends RuntimeException {

    public RoleNotFoundException(RoleId id) {
        super("role not found: " + id.value());
    }
}
