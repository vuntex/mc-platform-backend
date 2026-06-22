package com.mcplatform.domain.permission;

/** Raised when a grant is invalid (e.g. blank permission, or an expiry already in the past). Maps to 422. */
public final class InvalidGrantException extends RuntimeException {

    public InvalidGrantException(String message) {
        super(message);
    }
}
