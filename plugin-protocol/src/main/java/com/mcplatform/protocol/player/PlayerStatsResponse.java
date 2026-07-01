package com.mcplatform.protocol.player;

/**
 * Server-wide player counters for the web dashboard's stats line. Pure data (JDK only).
 *
 * @param totalPlayers all known players (every {@code player} row)
 * @param onlineNow    players currently connected (live presence set)
 * @param newThisWeek  registrations in the last 7 days (by {@code player.created_at})
 */
public record PlayerStatsResponse(long totalPlayers, long onlineNow, long newThisWeek) {
}
