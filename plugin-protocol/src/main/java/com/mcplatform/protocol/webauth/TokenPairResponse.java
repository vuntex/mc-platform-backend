package com.mcplatform.protocol.webauth;

/**
 * Login/refresh response. Carries the short-lived access token + both expiry timestamps. The refresh
 * token value is DELIBERATELY absent — it travels only in the httpOnly cookie (XSS protection, plan R5),
 * never in a JS-readable body. {@code refreshExpiresAtEpochMilli} only tells the client when re-login is
 * due. Pure data, JDK-only; never a secret/hash beyond the access token itself.
 */
public record TokenPairResponse(
        String accessToken, long accessExpiresAtEpochMilli, long refreshExpiresAtEpochMilli) {}
