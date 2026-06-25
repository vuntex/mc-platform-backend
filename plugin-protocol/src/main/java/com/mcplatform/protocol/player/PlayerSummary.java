package com.mcplatform.protocol.player;

import java.util.UUID;

/**
 * A player identified for display/selection in the web UI — UUID + cached Minecraft name. Pure data
 * (JDK only); carries no roles/permissions or other state.
 */
public record PlayerSummary(UUID uuid, String name) {
}
