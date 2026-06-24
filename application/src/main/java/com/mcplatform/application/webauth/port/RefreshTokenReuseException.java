package com.mcplatform.application.webauth.port;

/**
 * A already-rotated refresh token was presented again — treated strictly as a theft signal
 * (2026-06-24 clarification). All of the player's refresh tokens have been invalidated. Surfaces as 401
 * {@code web_auth_session_revoked}.
 */
public class RefreshTokenReuseException extends RuntimeException {
    public RefreshTokenReuseException(String message) {
        super(message);
    }
}
