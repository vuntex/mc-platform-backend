package com.mcplatform.domain.punishment;

import java.util.Objects;
import java.util.UUID;

/** Identity of a single punishment aggregate. */
public record PunishmentId(UUID value) {

    public PunishmentId {
        Objects.requireNonNull(value, "punishment id must not be null");
    }

    public static PunishmentId of(UUID value) {
        return new PunishmentId(value);
    }

    public static PunishmentId random() {
        return new PunishmentId(UUID.randomUUID());
    }
}
