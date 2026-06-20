package com.mcplatform.domain.punishment;

/**
 * The kinds of punishment. Each maps to an enforcement {@link PunishmentCategory} that decides
 * coexistence: WARN accumulates (NOTICE), CHATBAN is the single active chat restriction (CHAT),
 * TEMPBAN/PERMABAN share the single active access restriction (ACCESS).
 */
public enum PunishmentType {
    /** Verwarnung — no restriction, cumulative. */
    WARN(PunishmentCategory.NOTICE, false),
    /** Mute — time-bound chat restriction. */
    CHATBAN(PunishmentCategory.CHAT, true),
    /** Temporary ban — time-bound access restriction. */
    TEMPBAN(PunishmentCategory.ACCESS, true),
    /** Permanent ban — access restriction with no expiry. */
    PERMABAN(PunishmentCategory.ACCESS, false);

    private final PunishmentCategory category;
    private final boolean timeBound;

    PunishmentType(PunishmentCategory category, boolean timeBound) {
        this.category = category;
        this.timeBound = timeBound;
    }

    public PunishmentCategory category() {
        return category;
    }

    /** True for types that carry an expiry derived from a duration (TEMPBAN, CHATBAN). */
    public boolean isTimeBound() {
        return timeBound;
    }
}
