package com.mcplatform.domain.punishment;

/**
 * Raised when an issue request is well-formed but semantically invalid — e.g. a TEMPBAN/CHATBAN
 * without a positive duration. Maps to HTTP 422.
 */
public final class PunishmentValidationException extends RuntimeException {

    public PunishmentValidationException(String message) {
        super(message);
    }
}
