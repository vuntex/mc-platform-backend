package com.mcplatform.application.permission;

/**
 * Thrown when a caller HAS the required permission gate but the action exceeds its authority
 * (weight ceiling / target-authority / delegating a permission it does not hold) — spec 008.
 * Mapped to HTTP 403 {@code authority_ceiling}. Distinct from {@code PermissionDeniedException}
 * ("gate missing entirely").
 */
public final class InsufficientAuthorityException extends RuntimeException {

    public InsufficientAuthorityException(String message) {
        super(message);
    }
}
