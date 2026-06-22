package com.mcplatform.domain.permission;

/** Raised when role master data is invalid (e.g. blank name). Maps to HTTP 422. */
public final class RoleValidationException extends RuntimeException {

    public RoleValidationException(String message) {
        super(message);
    }
}
