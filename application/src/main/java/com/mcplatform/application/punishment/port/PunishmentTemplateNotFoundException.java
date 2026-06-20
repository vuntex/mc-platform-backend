package com.mcplatform.application.punishment.port;

/** Raised when applying a template key that does not exist or is inactive. Maps to HTTP 404. */
public final class PunishmentTemplateNotFoundException extends RuntimeException {

    public PunishmentTemplateNotFoundException(String key) {
        super("punishment template not found or inactive: " + key);
    }
}
