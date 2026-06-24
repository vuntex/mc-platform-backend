package com.mcplatform.protocol.webauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.protocol.core.HttpMethod;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebAuthEndpointsTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void expandsPlayerScopedTokenPaths() {
        assertThat(WebAuthEndpoints.REQUEST_LINK.expand(PLAYER))
                .isEqualTo("/api/players/00000000-0000-0000-0000-000000000001/web-auth/link-token");
        assertThat(WebAuthEndpoints.REQUEST_RESET.expand(PLAYER))
                .isEqualTo("/api/players/00000000-0000-0000-0000-000000000001/web-auth/reset-token");
    }

    @Test
    void redeemIsFlatWithNoResponseBody() {
        assertThat(WebAuthEndpoints.REDEEM.expand()).isEqualTo("/api/web-auth/redeem");
        assertThat(WebAuthEndpoints.REDEEM.method()).isEqualTo(HttpMethod.POST);
        assertThat(WebAuthEndpoints.REDEEM.requestType()).isEqualTo(RedeemRequest.class);
        assertThat(WebAuthEndpoints.REDEEM.responseType()).isEqualTo(Void.class);
    }

    @Test
    void tokenRequestsCarryMethodAndTypes() {
        assertThat(WebAuthEndpoints.REQUEST_LINK.method()).isEqualTo(HttpMethod.POST);
        assertThat(WebAuthEndpoints.REQUEST_LINK.requestType()).isEqualTo(Void.class);
        assertThat(WebAuthEndpoints.REQUEST_LINK.responseType()).isEqualTo(TokenResponse.class);
        assertThat(WebAuthEndpoints.REQUEST_RESET.responseType()).isEqualTo(TokenResponse.class);
    }
}
