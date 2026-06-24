package com.mcplatform.api.rest.support;

import com.mcplatform.application.webauth.TokenResult;
import com.mcplatform.protocol.webauth.TokenResponse;

/**
 * Maps the web-auth use-case result to the shared, dependency-free protocol DTO. The raw token is passed
 * through for the clickable link; no hash, no email/username ever appear in the response.
 */
public final class WebAuthMapper {

    private WebAuthMapper() {}

    public static TokenResponse tokenResponse(TokenResult result) {
        return new TokenResponse(result.rawToken(), result.purpose().name(), result.expiresAt().toEpochMilli());
    }
}
