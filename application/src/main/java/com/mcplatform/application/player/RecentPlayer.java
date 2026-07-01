package com.mcplatform.application.player;

import java.util.UUID;

/** Application-level result for one "recently active" player: display data + presence + last-seen millis. */
public record RecentPlayer(UUID uuid, String name, boolean online, long lastSeenEpochMilli) {
}
