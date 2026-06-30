package com.mcplatform.application.permission;

import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleInheritanceRepository;
import com.mcplatform.application.permission.port.RoleNotFoundException;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.domain.permission.EffectivePermissions;
import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.RankDisplay;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleHierarchy;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read use case: a player's effective permissions + display, and role detail views. Uses the pure
 * core-domain functions {@link EffectivePermissions}/{@link RankDisplay}/{@link RoleHierarchy}; respects
 * FR-005 (default fallback), FR-006 (expiry), FR-007a (deactivated roles excluded) and the transitive
 * inheritance union (FR-003). Not the hot path — the resolver answers {@code hasPermission} directly in
 * SQL (in lockstep with {@link RoleHierarchy}). Inheritance transitivity is a pre-layer here:
 * {@link EffectivePermissions} stays the single source of the flat set; {@link RoleHierarchy} adds the
 * per-permission provenance (FR-022a) only.
 */
public final class PermissionQueryService {

    /** A role together with its configured permissions and the ids of the roles it directly inherits. */
    public record RoleDetail(Role role, List<String> permissions, List<Long> inheritedRoleIds) {
    }

    private final RoleRepository roles;
    private final PlayerGrantRepository grants;
    private final RoleInheritanceRepository inheritance;
    private final Clock clock;

    public PermissionQueryService(RoleRepository roles, PlayerGrantRepository grants,
            RoleInheritanceRepository inheritance, Clock clock) {
        this.roles = roles;
        this.grants = grants;
        this.inheritance = inheritance;
        this.clock = clock;
    }

    public RoleDetail roleDetail(RoleId id) {
        Role r = roles.find(id).orElseThrow(() -> new RoleNotFoundException(id));
        return new RoleDetail(r, roles.permissionsOf(id), parentIds(id));
    }

    /** The direct parent (inherited) role ids of a role — backs the inheritance list endpoint (US4). */
    public List<Long> inheritanceParents(RoleId id) {
        roles.find(id).orElseThrow(() -> new RoleNotFoundException(id));
        return parentIds(id);
    }

    /**
     * The player's primary (display) rank — the highest-priority active DIRECT role per {@link RankDisplay}
     * (teamRank → weight → id), or the default role when no active rank applies. Display is NOT inherited
     * (FR-002), so this looks only at the player's direct roles.
     */
    public Role primaryRoleOf(PlayerId player) {
        Instant now = clock.instant();
        List<Role> activeRoles = grants.activeRoleGrants(player, now).stream()
                .map(g -> roles.find(g.role()).orElse(null))
                .filter(r -> r != null && r.active())
                .toList();
        return RankDisplay.choose(activeRoles).orElseGet(roles::findDefault);
    }

    public List<RoleDetail> allRoles() {
        return roles.findAll().stream()
                .map(r -> new RoleDetail(r, roles.permissionsOf(r.id()), parentIds(r.id())))
                .toList();
    }

    public PlayerPermissionsView effectiveFor(PlayerId player) {
        Instant now = clock.instant();
        List<RoleGrant> roleGrants = grants.activeRoleGrants(player, now);
        List<PermissionGrant> permGrants = grants.activePermissionGrants(player, now);

        Map<RoleId, Role> roleById = new HashMap<>();
        for (RoleGrant g : roleGrants) {
            roles.find(g.role()).ifPresent(r -> roleById.put(r.id(), r));
        }
        List<Role> activeRoles = roleById.values().stream().filter(Role::active).toList();
        Set<RoleId> directActiveIds = activeRoles.stream().map(Role::id).collect(Collectors.toSet());

        // Transitive expansion over the inheritance edges (FR-003). The active flag of parents is NOT
        // re-checked (FR-016) — only the direct roles are pre-filtered to active above.
        Set<RoleId> reachable = RoleHierarchy.reachable(directActiveIds, inheritance::directParents);
        Map<RoleId, List<String>> reachablePerms = new LinkedHashMap<>();
        for (RoleId id : reachable) {
            reachablePerms.put(id, roles.permissionsOf(id));
        }

        Role defaultRole = roles.findDefault();
        List<String> direct = permGrants.stream().map(PermissionGrant::permission).toList();
        // EffectivePermissions stays the single source of the flat set: empty reachable ⇒ default fallback.
        EffectivePermissions eff = EffectivePermissions.resolve(
                reachablePerms, roles.permissionsOf(defaultRole.id()), direct);

        // Provenance mirrors the flat set's default fallback: when the player has no active role, the
        // default role is the contributing role (FR-011/FR-022a).
        Set<RoleId> provenanceRoles = directActiveIds.isEmpty() ? Set.of(defaultRole.id()) : directActiveIds;
        Map<String, RoleHierarchy.Provenance> prov = RoleHierarchy.resolveWithProvenance(
                provenanceRoles, inheritance::directParents, roles::permissionsOf, direct);
        List<PlayerPermissionsView.PermissionOrigin> sources = prov.entrySet().stream()
                .map(e -> new PlayerPermissionsView.PermissionOrigin(
                        e.getKey(), e.getValue().own(),
                        e.getValue().sources().stream().map(RoleId::value).sorted().toList()))
                .toList();

        Role displayRole = RankDisplay.choose(activeRoles).orElse(defaultRole);

        List<PlayerPermissionsView.GrantSummary> roleSummaries = roleGrants.stream()
                .map(g -> new PlayerPermissionsView.GrantSummary(
                        roleById.containsKey(g.role()) ? roleById.get(g.role()).name() : "role#" + g.role().value(),
                        g.expiresAt(), g.issuedBy(), g.reason()))
                .toList();
        List<PlayerPermissionsView.GrantSummary> permSummaries = permGrants.stream()
                .map(g -> new PlayerPermissionsView.GrantSummary(g.permission(), g.expiresAt(), g.issuedBy(), g.reason()))
                .toList();
        PlayerPermissionsView.Display display = new PlayerPermissionsView.Display(
                displayRole.displayName(), displayRole.color(), displayRole.prefix(), displayRole.suffix(),
                displayRole.tabListColor(), displayRole.tabListIcon(), displayRole.displayIcon());

        return new PlayerPermissionsView(player.value(), roleSummaries, permSummaries, eff.permissions(),
                sources, display);
    }

    private List<Long> parentIds(RoleId id) {
        return inheritance.directParents(id).stream().map(RoleId::value).toList();
    }
}
