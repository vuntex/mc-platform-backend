package com.mcplatform.application.webauth.port;

/**
 * Refresh token unknown, expired, or otherwise unusable (but not a detected reuse). Uniform — leaks no
 * existence. Surfaces as 401 {@code web_auth_refresh_invalid}.
 */
public class RefreshTokenInvalidException extends RuntimeException {
    public RefreshTokenInvalidException(String message) {
        super(message);
    }
}
