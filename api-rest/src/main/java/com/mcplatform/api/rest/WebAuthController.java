package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.WebAuthMapper;
import com.mcplatform.application.webauth.WebAuthService;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.webauth.RedeemRequest;
import com.mcplatform.protocol.webauth.TokenResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the web-auth bridge. Token requests are player-scoped and called by the plugin
 * (the in-game session proves the identity → no permission gate, no name lookup); redeem is flat and
 * called by the webinterface. Preconditions, password policy and token validity are all backend-side.
 */
@RestController
public class WebAuthController {

    private final WebAuthService webAuth;

    public WebAuthController(WebAuthService webAuth) {
        this.webAuth = webAuth;
    }

    /** Request a LINK token (in-game). 409 if an account already exists, 429 on cooldown. */
    @PostMapping("/api/players/{uuid}/web-auth/link-token")
    public TokenResponse linkToken(@PathVariable UUID uuid) {
        return WebAuthMapper.tokenResponse(webAuth.requestLinkToken(PlayerId.of(uuid)));
    }

    /** Request a RESET token (in-game). 409 if no account exists, 429 on cooldown. */
    @PostMapping("/api/players/{uuid}/web-auth/reset-token")
    public TokenResponse resetToken(@PathVariable UUID uuid) {
        return WebAuthMapper.tokenResponse(webAuth.requestResetToken(PlayerId.of(uuid)));
    }

    /** Redeem a token + set the password (webinterface). 410 invalid token, 422 weak password, 409 race. */
    @PostMapping("/api/web-auth/redeem")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redeem(@RequestBody RedeemRequest request) {
        webAuth.redeem(request.token(), request.password());
    }
}
