package com.mcplatform.domain.webauth;

/** Raised when a password violates the policy (length outside 8..64). Maps to HTTP 422. */
public final class InvalidPasswordException extends RuntimeException {

    public InvalidPasswordException(String message) {
        super(message);
    }
}
