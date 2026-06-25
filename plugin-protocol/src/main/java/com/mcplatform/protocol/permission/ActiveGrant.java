package com.mcplatform.protocol.permission;

import java.util.UUID;

/**
 * An active grant in a player's permission view. {@code label} is the role name (rank grant) or the
 * permission string (direct grant); {@code expiresAtEpochMilli == null} means permanent. {@code issuedBy}
 * is the granting actor's UUID; {@code issuedByName} is that actor's cached player name for display, or
 * null if it could not be resolved (e.g. the system actor or an actor with no player row). The internal
 * plugin path leaves {@code issuedByName} null; the web surface fills it.
 */
public record ActiveGrant(String label, Long expiresAtEpochMilli, UUID issuedBy, String issuedByName,
        String reason) {
}
