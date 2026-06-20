package com.mcplatform.domain.punishment;

import java.time.Duration;
import java.util.Objects;

/**
 * A reusable punishment preset (e.g. "Cheating → 7d Tempban"). Carries the {@code requiredPermission}
 * that the executing team member must hold to apply it — the backend-authoritative gate. Pure config
 * (managed via the web interface); applying it pre-fills type/reason/duration on an issue.
 *
 * <p>{@code duration} is null for non-time-bound types (WARN, PERMABAN) and must be a positive
 * duration for time-bound types (TEMPBAN, CHATBAN).
 */
public record PunishmentTemplate(
        String key,
        PunishmentType type,
        String defaultReason,
        Duration duration,          // null = none (WARN, PERMABAN)
        String requiredPermission,
        boolean active) {

    public PunishmentTemplate {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultReason, "defaultReason");
        Objects.requireNonNull(requiredPermission, "requiredPermission");
        if (key.isBlank()) {
            throw new IllegalArgumentException("template key must not be blank");
        }
        if (requiredPermission.isBlank()) {
            throw new IllegalArgumentException("requiredPermission must not be blank");
        }
        if (type.isTimeBound() && (duration == null || duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException("template " + key + " (" + type + ") needs a positive duration");
        }
    }
}
