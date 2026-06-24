package com.mcplatform.application.webauth.port;

/** Access token malformed, tampered, or expired. Surfaces as 401 (via the security entry point). */
public class AccessTokenInvalidException extends RuntimeException {
    public AccessTokenInvalidException(String message) {
        super(message);
    }
}
