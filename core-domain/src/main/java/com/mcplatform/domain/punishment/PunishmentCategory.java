package com.mcplatform.domain.punishment;

/**
 * Enforcement category of a punishment — the dimension that governs coexistence. An {@code exclusive}
 * category admits at most one active punishment per player at a time; a non-exclusive one accumulates.
 */
public enum PunishmentCategory {
    /** Verwarnungen — purely a record, no restriction. Multiple may be active at once. */
    NOTICE(false),
    /** Chat restriction (mute). At most one active per player. */
    CHAT(true),
    /** Server-access restriction (ban). At most one active per player. */
    ACCESS(true);

    private final boolean exclusive;

    PunishmentCategory(boolean exclusive) {
        this.exclusive = exclusive;
    }

    /** True if at most one punishment of this category may be active for a player at a time. */
    public boolean isExclusive() {
        return exclusive;
    }
}
