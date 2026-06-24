package com.mcplatform.application.webauth.port;

/**
 * Raised on redemption when the token is unknown, expired or already used. Deliberately uniform — it
 * never reveals which case occurred or whether a token ever existed (spec FR-019 / SC-005). Maps to
 * HTTP 410 (Gone).
 */
public final class TokenInvalidException extends RuntimeException {

    public TokenInvalidException(String message) {
        super(message);
    }
}
