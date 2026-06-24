package com.mcplatform.application.webauth;

import java.time.Instant;

/**
 * Result of a login/refresh: the freshly issued access token plus the RAW refresh token and both expiry
 * instants. The api-rest layer puts the access token in the response body and the raw refresh token into
 * an httpOnly cookie — the raw refresh value never reaches a JS-readable body (plan R5).
 */
public record SessionTokens(
        String accessToken, Instant accessExpiresAt, String refreshRawToken, Instant refreshExpiresAt) {}
