package com.mcplatform.protocol.session;

/**
 * Request body carrying a player's current name — used both to upsert player master data and as the
 * session-join body (the plugin reports the name it saw at join). Pure data (JDK only).
 */
public record PlayerRequest(String name) {
}
