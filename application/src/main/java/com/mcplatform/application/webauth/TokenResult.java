package com.mcplatform.application.webauth;

import com.mcplatform.domain.webauth.TokenPurpose;
import java.time.Instant;

/**
 * Result of a token request: the raw token to hand to the player (only known at generation time), its
 * purpose and expiry. The raw value is delivered to the plugin as a clickable link and never persisted
 * in clear (only its hash is stored).
 */
public record TokenResult(String rawToken, TokenPurpose purpose, Instant expiresAt) {
}
