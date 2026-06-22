package com.mcplatform.protocol.permission;

import java.util.UUID;

/**
 * An active grant in a player's permission view. {@code label} is the role name (rank grant) or the
 * permission string (direct grant); {@code expiresAtEpochMilli == null} means permanent.
 */
public record ActiveGrant(String label, Long expiresAtEpochMilli, UUID issuedBy, String reason) {
}
