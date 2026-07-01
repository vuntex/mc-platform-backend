package com.mcplatform.protocol.session;

import com.mcplatform.protocol.core.EndpointDescriptor;
import com.mcplatform.protocol.core.HttpMethod;

/**
 * Player/session REST endpoints as constants. {@link #UPSERT_PLAYER} writes master data (204, no
 * response body); {@link #JOIN} is the plugin's "player entered the server" call that ensures identity
 * and default balances and returns them; {@link #LEAVE} is the "player disconnected" call that clears
 * the player's live presence.
 */
public final class SessionEndpoints {

    /** PUT player master data (upsert). No response body (204). */
    public static final EndpointDescriptor<PlayerRequest, Void> UPSERT_PLAYER =
            new EndpointDescriptor<>(HttpMethod.PUT, "/api/players/{uuid}", PlayerRequest.class, Void.class);

    /** POST session join — ensures the player + default balances, returns them. */
    public static final EndpointDescriptor<PlayerRequest, SessionJoinResponse> JOIN =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/players/{uuid}/session/join",
                    PlayerRequest.class, SessionJoinResponse.class);

    /** POST session leave — clears live presence (marks the player offline). No body either way (204). */
    public static final EndpointDescriptor<Void, Void> LEAVE =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/players/{uuid}/session/leave",
                    Void.class, Void.class);

    private SessionEndpoints() {}
}
