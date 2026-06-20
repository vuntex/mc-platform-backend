package com.mcplatform.protocol.punishment;

import com.mcplatform.protocol.core.EndpointDescriptor;
import com.mcplatform.protocol.core.HttpMethod;

/**
 * The punishment REST endpoints as constants, so clients reference them by name instead of hard-coding
 * paths/methods/types. The player path template takes {@code uuid}; revoke takes the punishment
 * {@code id}. List endpoints carry an array response type.
 */
public final class PunishmentEndpoints {

    private static final String PER_PLAYER = "/api/players/{uuid}/punishments";

    /** GET the player's currently active punishments. */
    public static final EndpointDescriptor<Void, PunishmentResponse[]> LIST_ACTIVE =
            new EndpointDescriptor<>(HttpMethod.GET, PER_PLAYER + "/active", Void.class, PunishmentResponse[].class);

    /** POST issue a punishment directly. */
    public static final EndpointDescriptor<IssueRequest, PunishmentResponse> ISSUE =
            new EndpointDescriptor<>(HttpMethod.POST, PER_PLAYER, IssueRequest.class, PunishmentResponse.class);

    /** POST issue a punishment from a template. */
    public static final EndpointDescriptor<IssueFromTemplateRequest, PunishmentResponse> ISSUE_FROM_TEMPLATE =
            new EndpointDescriptor<>(HttpMethod.POST, PER_PLAYER + "/from-template",
                    IssueFromTemplateRequest.class, PunishmentResponse.class);

    /** POST revoke a punishment by id. */
    public static final EndpointDescriptor<RevokeRequest, PunishmentResponse> REVOKE =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/punishments/{id}/revoke",
                    RevokeRequest.class, PunishmentResponse.class);

    /** GET the templates with a {@code canApply} flag for the querying member ({@code ?staff=<uuid>}). */
    public static final EndpointDescriptor<Void, TemplateResponse[]> LIST_TEMPLATES =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/punishments/templates", Void.class, TemplateResponse[].class);

    private PunishmentEndpoints() {}
}
