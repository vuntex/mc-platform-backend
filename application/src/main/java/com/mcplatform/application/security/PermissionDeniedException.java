package com.mcplatform.application.security;

import java.util.UUID;

/** Raised when the executing team member lacks the required permission. Maps to HTTP 403. */
public final class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(UUID actor, String permission) {
        super("actor " + actor + " lacks required permission: " + permission);
    }
}
