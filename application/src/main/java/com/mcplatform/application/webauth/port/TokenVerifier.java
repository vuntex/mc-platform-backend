package com.mcplatform.application.webauth.port;

import com.mcplatform.domain.player.PlayerId;

/**
 * Outbound port verifying an access token's signature + expiry and extracting the identity. Consumed by
 * the api-rest JWT filter (which depends only on this port, not on the JWT library). Implementation lives
 * in {@code app}. Authorization is NOT done here — only authentication (identity); rights come from the
 * {@code PermissionResolver} at request time (Constitution §12).
 */
public interface TokenVerifier {

    /**
     * @return the subject identity if the token's signature and expiry are valid
     * @throws AccessTokenInvalidException if the token is malformed, tampered, or expired
     */
    PlayerId verify(String accessToken);
}
