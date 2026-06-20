package com.mcplatform.api.rest.support;

import com.mcplatform.application.punishment.TemplateView;
import com.mcplatform.domain.punishment.Punishment;
import com.mcplatform.domain.punishment.PunishmentTemplate;
import com.mcplatform.domain.punishment.PunishmentTxId;
import com.mcplatform.domain.punishment.PunishmentType;
import com.mcplatform.protocol.punishment.PunishmentResponse;
import com.mcplatform.protocol.punishment.TemplateResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Maps between the shared dependency-free punishment protocol DTOs and the domain/application types.
 * The protocol records stay pure data (JDK only); all domain coupling for the punishment endpoints
 * lives here. Epoch-milli {@code 0} encodes an absent {@code expiresAt}/{@code revokedAt}.
 */
public final class PunishmentMapper {

    private PunishmentMapper() {}

    public static PunishmentResponse punishmentResponse(Punishment p, Instant now) {
        return new PunishmentResponse(
                p.id().value(),
                p.player().value(),
                p.type().name(),
                p.reason(),
                p.issuedBy().value(),
                p.issuedAt().toEpochMilli(),
                p.expiresAt() == null ? 0L : p.expiresAt().toEpochMilli(),
                p.revokedBy() == null ? null : p.revokedBy().value(),
                p.revokedAt() == null ? 0L : p.revokedAt().toEpochMilli(),
                p.isActive(now),
                p.version());
    }

    public static TemplateResponse templateResponse(TemplateView view) {
        PunishmentTemplate t = view.template();
        return new TemplateResponse(
                t.key(),
                t.type().name(),
                t.defaultReason(),
                t.duration() == null ? 0L : t.duration().toMillis(),
                t.requiredPermission(),
                t.active(),
                view.canApply());
    }

    /** Parses the wire type string to the domain enum; an unknown value is a 400 (bad request). */
    public static PunishmentType parseType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        try {
            return PunishmentType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown punishment type: " + type);
        }
    }

    /** A reason is mandatory on a direct issue. */
    public static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        return reason;
    }

    /** Stable id from the request, or a fresh random one — keeps the action idempotent on retry. */
    public static PunishmentTxId txId(UUID transactionId) {
        return transactionId != null ? PunishmentTxId.of(transactionId) : PunishmentTxId.random();
    }

    public static Duration duration(Long millis) {
        return millis == null ? null : Duration.ofMillis(millis);
    }

    /** Returns {@code source} when present, otherwise the endpoint-specific {@code fallback}. */
    public static String sourceOr(String source, String fallback) {
        return (source == null || source.isBlank()) ? fallback : source;
    }
}
