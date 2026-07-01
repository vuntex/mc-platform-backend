package com.mcplatform.application.player;

/** Application-level server-wide player counters for the web dashboard stats line. */
public record PlayerStats(long totalPlayers, long onlineNow, long newThisWeek) {
}
