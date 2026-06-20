package com.mcplatform.application.punishment.port;

import com.mcplatform.domain.punishment.PunishmentId;

/** Raised when an operation targets a punishment id that does not exist. Maps to HTTP 404. */
public final class PunishmentNotFoundException extends RuntimeException {

    public PunishmentNotFoundException(PunishmentId id) {
        super("punishment not found: " + id.value());
    }
}
