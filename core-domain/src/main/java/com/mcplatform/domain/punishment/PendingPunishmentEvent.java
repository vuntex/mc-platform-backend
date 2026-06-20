package com.mcplatform.domain.punishment;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;

/**
 * A punishment event computed by the domain but not yet persisted (no sequence_no assigned yet).
 * For {@code ISSUED}, {@code type} is set, {@code reason} is the issue reason, {@code actor} is the
 * issuer and {@code expiresAt} the (optional) computed expiry. For {@code REVOKED}, {@code type} and
 * {@code expiresAt} are null, {@code reason} is the revoke reason and {@code actor} is the revoker.
 */
public record PendingPunishmentEvent(
        PunishmentId punishmentId,
        PlayerId player,
        PunishmentEventType eventType,
        PunishmentType type,        // null on REVOKED
        String reason,
        PlayerId actor,
        Instant occurredAt,
        Instant expiresAt,          // null = permanent / not applicable / revoke
        PunishmentTxId transactionId,
        String source) {

    public PendingPunishmentEvent {
        Objects.requireNonNull(punishmentId, "punishmentId");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(source, "source");
        if (eventType == PunishmentEventType.ISSUED) {
            Objects.requireNonNull(type, "type is required on an ISSUED event");
        }
    }
}
