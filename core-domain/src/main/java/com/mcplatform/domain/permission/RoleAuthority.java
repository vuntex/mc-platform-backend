package com.mcplatform.domain.permission;

/**
 * Pure authority-ceiling logic for privilege-escalation prevention (spec 008) — no DB, no framework.
 * The authority axis is the role {@code weight}: an actor may only manage roles/targets strictly below
 * its own authority (non-top), while the top tier ({@code topTier=true}) may manage its own level too
 * (≤). Nobody may exceed its own authority (the ceiling). Unit-testable in isolation.
 */
public final class RoleAuthority {

    private RoleAuthority() {}

    /** Whether an actor may manage a role of {@code targetWeight}: non-top strict {@code <}, top-tier {@code ≤}. */
    public static boolean canManageWeight(int targetWeight, int authorityWeight, boolean topTier) {
        return withinAuthority(targetWeight, authorityWeight, topTier);
    }

    /** Whether an actor may act on a target whose own authority is {@code targetAuthority} (same rule). */
    public static boolean canManageTarget(int targetAuthority, int authorityWeight, boolean topTier) {
        return withinAuthority(targetAuthority, authorityWeight, topTier);
    }

    /** A permission string that grants a whole namespace: {@code "*"} or ends with {@code ".*"}. */
    public static boolean isWildcard(String permission) {
        return permission != null && (permission.equals("*") || permission.endsWith(".*"));
    }

    private static boolean withinAuthority(int value, int authorityWeight, boolean topTier) {
        return topTier ? value <= authorityWeight : value < authorityWeight;
    }
}
