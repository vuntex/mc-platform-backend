package com.mcplatform.protocol.punishment;

import java.util.Objects;
import java.util.UUID;

/**
 * Pub/Sub event published whenever a punishment changes (issued or revoked) — the shared contract
 * between backend and plugin. Pure data: no framework and no core-domain dependency, so {@code type}
 * and {@code action} are {@code String}s (values mirror the domain enums). Wire format lives in
 * {@link PunishmentChangedEventCodec}, carried inside a
 * {@link com.mcplatform.protocol.core.MessageEnvelope}.
 */
public record PunishmentChangedEvent(
        UUID punishmentId,
        UUID playerUuid,
        String type,                  // WARN|CHATBAN|TEMPBAN|PERMABAN
        String action,                // ISSUED|REVOKED
        String reason,
        UUID actor,                   // issuedBy on ISSUED, revokedBy on REVOKED
        long expiresAtEpochMilli,     // 0 = permanent / not applicable
        long version,                 // sequence_no of the originating punishment_event
        long timestampEpochMilli) {

    public PunishmentChangedEvent {
        Objects.requireNonNull(punishmentId, "punishmentId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actor, "actor");
    }
}
