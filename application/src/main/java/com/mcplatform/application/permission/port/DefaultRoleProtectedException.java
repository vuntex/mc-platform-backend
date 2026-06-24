package com.mcplatform.application.permission.port;

/** Raised when an operation would delete or deactivate the protected default role (FR-012). Maps to 409. */
public final class DefaultRoleProtectedException extends RuntimeException {

    public DefaultRoleProtectedException(String message) {
        super(message);
    }
}
