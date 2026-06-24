package com.mcplatform.domain.permission;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Deterministic display selection when a player holds several active ranks (FR-019). Ordering:
 * {@code teamRank} desc → {@code weight} desc → {@code RoleId} asc — fully stable, independent of grant
 * order (SC-005). {@code weight}/{@code teamRank} influence ONLY this choice, never resolution (FR-010).
 *
 * <p>Input is the set of ACTIVE roles the player holds via active grants (FR-007a); a deactivated role
 * is never passed in. Returns empty when there is none — the caller then uses the default role for
 * display.
 */
public final class RankDisplay {

    // teamRank: false < true, so max() prefers team ranks. weight: higher wins. id: reversed so the
    // SMALLER id wins the max() tie-break (FR-019: RoleId asc).
    private static final Comparator<Role> BY_DISPLAY_PRIORITY =
            Comparator.comparing(Role::teamRank)
                    .thenComparingInt(Role::weight)
                    .thenComparing(r -> r.id().value(), Comparator.reverseOrder());

    private RankDisplay() {}

    public static Optional<Role> choose(Collection<Role> activeRoles) {
        return activeRoles.stream()
                .filter(Role::active)
                .max(BY_DISPLAY_PRIORITY);
    }
}
