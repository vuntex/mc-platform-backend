package com.mcplatform.application.permission;

import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleInheritanceRepository;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleAuthority;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleHierarchy;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authority-ceiling engine (spec 008): computes an actor's {@code authorityWeight} (max weight over its
 * reachable roles — active grants + transitive inheritance, NOT {@code primaryRoleOf} which prioritises
 * teamRank), the system-wide {@code topWeight}, and the guard/read predicates used by
 * {@link PermissionAdminService} (writes) and the web read layer. Framework-free; delegates the pure
 * comparison to {@link RoleAuthority}. Authority is weight-only — holding {@code *} does not raise it.
 */
public final class PermissionAuthorityService {

    private final RoleRepository roles;
    private final PlayerGrantRepository grants;
    private final RoleInheritanceRepository inheritance;
    private final PermissionResolver resolver;
    private final Clock clock;

    public PermissionAuthorityService(RoleRepository roles, PlayerGrantRepository grants,
            RoleInheritanceRepository inheritance, PermissionResolver resolver, Clock clock) {
        this.roles = roles;
        this.grants = grants;
        this.inheritance = inheritance;
        this.resolver = resolver;
        this.clock = clock;
    }

    // --- authority computation --------------------------------------------

    /** Highest weight over the actor's reachable roles (active grants + transitive inheritance); default (0) if none. */
    public int authorityWeight(UUID actor) {
        Set<RoleId> direct = new LinkedHashSet<>();
        for (RoleGrant grant : grants.activeRoleGrants(PlayerId.of(actor), clock.instant())) {
            direct.add(grant.role());
        }
        if (direct.isEmpty()) {
            return roles.findDefault().weight();
        }
        int max = Integer.MIN_VALUE;
        for (RoleId id : RoleHierarchy.reachable(direct, inheritance::directParents)) {
            Optional<Role> role = roles.find(id);
            if (role.isPresent() && role.get().active()) {
                max = Math.max(max, role.get().weight());
            }
        }
        return max == Integer.MIN_VALUE ? roles.findDefault().weight() : max;
    }

    /** Highest weight among all active roles (the top tier). */
    public int topWeight() {
        return roles.findAll().stream().filter(Role::active).mapToInt(Role::weight).max().orElse(0);
    }

    public boolean isTopTier(UUID actor) {
        return authorityWeight(actor) == topWeight();
    }

    // --- write guards (throw InsufficientAuthorityException = 403) ---------

    /** The actor may manage {@code role} (by its weight). */
    public void requireCanManageRole(UUID actor, Role role) {
        requireCanManageWeight(actor, role.weight());
    }

    /** The actor may create/lift a role to {@code weight} (never above its own authority; non-top strict <). */
    public void requireCanManageWeight(UUID actor, int weight) {
        int authority = authorityWeight(actor);
        if (!RoleAuthority.canManageWeight(weight, authority, authority == topWeight())) {
            throw new InsufficientAuthorityException(
                    "actor " + actor + " may not manage a role of weight " + weight + " (authority " + authority + ")");
        }
    }

    /** The actor may act on {@code target} (by the target's current authority). */
    public void requireCanManageTarget(UUID actor, PlayerId target) {
        int authority = authorityWeight(actor);
        int targetAuthority = authorityWeight(target.value());
        if (!RoleAuthority.canManageTarget(targetAuthority, authority, authority == topWeight())) {
            throw new InsufficientAuthorityException(
                    "actor " + actor + " may not manage target of authority " + targetAuthority
                            + " (authority " + authority + ")");
        }
    }

    /** The actor may delegate {@code permission}: must hold it; any wildcard requires holding {@code *}. */
    public void requireCanDelegate(UUID actor, String permission) {
        boolean held = RoleAuthority.isWildcard(permission)
                ? resolver.hasPermission(actor, "*")
                : resolver.hasPermission(actor, permission);
        if (!held) {
            throw new InsufficientAuthorityException(
                    "actor " + actor + " may not delegate a permission it does not hold: " + permission);
        }
    }

    // --- lockout guards (throw LastTopTierException = 409) -----------------

    /** True only when some role sits strictly above the default — otherwise there is no top tier to protect. */
    private boolean hasRealTopTier() {
        return topWeight() > roles.findDefault().weight();
    }

    /** Block if revoking {@code role} from {@code player} would empty the top tier. */
    public void requireNotLastTopTierOnRevoke(PlayerId player, Role role) {
        if (!hasRealTopTier() || role.weight() != topWeight()) {
            return; // no real top tier, or not a top-tier role → cannot affect the top tier
        }
        Set<UUID> before = topTierHolders(null, null);
        if (before.isEmpty()) {
            return;
        }
        if (topTierHolders(role.id(), player).isEmpty()) {
            throw new LastTopTierException("cannot remove the last top-tier holder");
        }
    }

    /** Block if deleting {@code role} would empty the top tier. */
    public void requireNotLastTopTierOnDelete(Role role) {
        requireTopTierSurvivesRoleRemoval(role, role.weight());
    }

    /** Block if lowering {@code role}'s weight below the top would empty the top tier. */
    public void requireNotLastTopTierOnWeightChange(Role role, int newWeight) {
        if (newWeight >= topWeight()) {
            return; // still at (or above) the top → no tier loss
        }
        requireTopTierSurvivesRoleRemoval(role, role.weight());
    }

    private void requireTopTierSurvivesRoleRemoval(Role role, int roleWeight) {
        if (!hasRealTopTier() || roleWeight != topWeight()) {
            return;
        }
        Set<UUID> before = topTierHolders(null, null);
        if (before.isEmpty()) {
            return;
        }
        if (topTierHolders(role.id(), null).isEmpty()) {
            throw new LastTopTierException("cannot remove the last top-tier role");
        }
    }

    /**
     * Accounts holding an active role at the current top weight, optionally simulating a removal:
     * {@code excludeRole} + {@code excludePlayer} = remove that one grant (revoke); {@code excludeRole}
     * + {@code null} player = remove the whole role (delete/weight-lower).
     */
    private Set<UUID> topTierHolders(RoleId excludeRole, PlayerId excludePlayer) {
        int top = topWeight();
        Instant now = clock.instant();
        Set<UUID> holders = new HashSet<>();
        for (Role role : roles.findAll()) {
            if (!role.active() || role.weight() != top) {
                continue;
            }
            boolean isExcludedRole = role.id().equals(excludeRole);
            if (isExcludedRole && excludePlayer == null) {
                continue; // whole role removed
            }
            for (PlayerId holder : grants.activeHoldersOf(role.id(), now)) {
                if (isExcludedRole && holder.equals(excludePlayer)) {
                    continue; // this player's grant to this role removed
                }
                holders.add(holder.value());
            }
        }
        return holders;
    }

    // --- read helpers ------------------------------------------------------

    /** All roles the actor may see/manage (weight ceiling) — for lists/pickers (FR-009). */
    public List<Role> visibleRoles(UUID actor) {
        int authority = authorityWeight(actor);
        boolean top = authority == topWeight();
        return roles.findAll().stream()
                .filter(r -> RoleAuthority.canManageWeight(r.weight(), authority, top))
                .toList();
    }

    /** Whether the actor may view a single role's detail (FR-009a). */
    public boolean canViewRole(UUID actor, Role role) {
        int authority = authorityWeight(actor);
        return RoleAuthority.canManageWeight(role.weight(), authority, authority == topWeight());
    }

    /** Whether the actor may view a target player's permission/role detail (FR-010). */
    public boolean canViewTarget(UUID actor, PlayerId target) {
        if (actor.equals(target.value())) {
            return true; // one may always view one's OWN permissions, regardless of authority
        }
        int authority = authorityWeight(actor);
        int targetAuthority = authorityWeight(target.value());
        return RoleAuthority.canManageTarget(targetAuthority, authority, authority == topWeight());
    }
}
