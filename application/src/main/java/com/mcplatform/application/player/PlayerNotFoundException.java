package com.mcplatform.application.player;

import java.util.UUID;

/**
 * Raised when a UUID cannot be resolved to a known player (no cached name / no player row). Surfaces as
 * 404 {@code player_not_found} on the web read surface. Read-only concern — never thrown on a write path.
 */
public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(UUID uuid) {
        super("no player found for uuid " + uuid);
    }
}
