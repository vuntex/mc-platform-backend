package com.mcplatform.domain.punishment;

import com.mcplatform.domain.player.PlayerId;

/**
 * Raised when issuing or revoking a punishment would violate a coexistence invariant — e.g. a second
 * active punishment in an exclusive category (CHAT, ACCESS), or revoking one that is already revoked.
 * Maps to HTTP 409.
 */
public final class PunishmentConflictException extends RuntimeException {

    public PunishmentConflictException(PlayerId player, PunishmentCategory category) {
        super("player " + player.value() + " already has an active " + category + " punishment");
    }

    public PunishmentConflictException(String message) {
        super(message);
    }
}
