package com.mcplatform.protocol.webauth;

import com.mcplatform.protocol.core.EndpointDescriptor;
import com.mcplatform.protocol.core.HttpMethod;

/**
 * The web-auth REST endpoints as constants, so clients reference them by name instead of hard-coding
 * paths. Token requests are player-scoped (the plugin knows the UUID from the in-game session); redeem
 * is flat (the webinterface knows only the token, which carries the identity). This feature has no live
 * event, so there is NO {@code MessageCodec}/{@code Channel} and {@code PlatformProtocol.create()} is
 * not touched — only these pure-data descriptors are added.
 */
public final class WebAuthEndpoints {

    /** POST request a LINK token for a player (in-game; precondition: no account yet). */
    public static final EndpointDescriptor<Void, TokenResponse> REQUEST_LINK =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/players/{uuid}/web-auth/link-token",
                    Void.class, TokenResponse.class);

    /** POST request a RESET token for a player (in-game; precondition: account exists). */
    public static final EndpointDescriptor<Void, TokenResponse> REQUEST_RESET =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/players/{uuid}/web-auth/reset-token",
                    Void.class, TokenResponse.class);

    /** POST redeem a token with a new password (webinterface). No response body (204). */
    public static final EndpointDescriptor<RedeemRequest, Void> REDEEM =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/web-auth/redeem",
                    RedeemRequest.class, Void.class);

    /** POST login: MC name + password → access token (refresh delivered as an httpOnly cookie). */
    public static final EndpointDescriptor<LoginRequest, TokenPairResponse> LOGIN =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/web-auth/login",
                    LoginRequest.class, TokenPairResponse.class);

    /** POST refresh: refresh cookie → new access token (rotates the refresh cookie). No request body. */
    public static final EndpointDescriptor<Void, TokenPairResponse> REFRESH =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/web-auth/session/refresh",
                    Void.class, TokenPairResponse.class);

    /** POST logout: invalidate the presented refresh token. Idempotent, no response body (204). */
    public static final EndpointDescriptor<Void, Void> LOGOUT =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/web-auth/session/logout",
                    Void.class, Void.class);

    private WebAuthEndpoints() {}
}
