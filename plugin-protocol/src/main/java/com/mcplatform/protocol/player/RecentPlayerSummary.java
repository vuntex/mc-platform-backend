package com.mcplatform.protocol.player;

import java.util.UUID;

/**
 * A player row for the web dashboard's "online / recently active" list — display data plus a live
 * presence flag and the last-seen timestamp (epoch millis, as everywhere else). Pure data (JDK only);
 * carries no roles/permissions or other state. The web layer orders these online-first, then by
 * {@code lastSeenEpochMilli} descending.
 */
public record RecentPlayerSummary(UUID uuid, String name, boolean online, long lastSeenEpochMilli) {
}
