package com.mcplatform.application.webauth.port;

/**
 * Raised when a redemption races the account state: a LINK token but the account now exists, or a RESET
 * token but the account is now gone. Maps to HTTP 409.
 */
public final class WebAccountConflictException extends RuntimeException {

    public WebAccountConflictException(String message) {
        super(message);
    }
}
