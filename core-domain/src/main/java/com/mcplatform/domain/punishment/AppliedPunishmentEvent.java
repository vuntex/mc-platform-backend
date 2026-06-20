package com.mcplatform.domain.punishment;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;

/**
 * A persisted punishment event: a {@link PendingPunishmentEvent} enriched with the global ordering
 * ({@code version} = sequence_no) and the time it was recorded. This is what gets published as a
 * live-update event on {@code mc:punishment:changed}.
 */
public record AppliedPunishmentEvent(
        PunishmentId punishmentId,
        PlayerId player,
        PunishmentType type,
        PunishmentEventType action,
        String reason,
        PlayerId actor,
        Instant expiresAt,   // null = permanent / not applicable
        long version,
        Instant occurredAt) {

    public AppliedPunishmentEvent {
        Objects.requireNonNull(punishmentId, "punishmentId");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
