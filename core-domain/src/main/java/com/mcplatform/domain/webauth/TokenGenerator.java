package com.mcplatform.domain.webauth;

/**
 * Outbound port producing a fresh, cryptographically random, URL-safe token (≥128 bit, spec FR-024).
 * The implementation (SecureRandom) lives in the {@code app} composition root; the token is the secret
 * delivered to the player and is stored only as a hash (research R3).
 */
public interface TokenGenerator {

    /** A new high-entropy, URL-safe token value. */
    String newToken();
}
