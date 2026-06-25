package com.mcplatform.application.permission;

import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleNotFoundException;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.domain.permission.EffectivePermissions;
import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.RankDisplay;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read use case: a player's effective permissions + display, and role detail views. Uses the pure
 * core-domain functions {@link EffectivePermissions}/{@link RankDisplay}; respects FR-005 (default
 * fallback), FR-006 (expiry) and FR-007a (deactivated roles excluded). Not the hot path — the resolver
 * answers {@code hasPermission} directly in SQL.
 */
public final class PermissionQueryService {

    /** A role together with its configured permissions (master data view). */
    public record RoleDetail(Role role, List<String> permissions) {
    }

    private final RoleRepository roles;
    private final PlayerGrantRepository grants;
    private final Clock clock;

    public PermissionQueryService(RoleRepository roles, PlayerGrantRepository grants, Clock clock) {
        this.roles = roles;
        this.grants = grants;
        this.clock = clock;
    }

    public RoleDetail roleDetail(RoleId id) {
        Role r = roles.find(id).orElseThrow(() -> new RoleNotFoundException(id));
        return new RoleDetail(r, roles.permissionsOf(id));
    }

    /**
     * The player's primary (display) rank — the highest-priority active role per {@link RankDisplay}
     * (teamRank → weight → id), or the default role when no active rank applies. Same selection the game
     * uses for prefix/color, so a web badge stays consistent with in-game.
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
                .map(r -> new RoleDetail(r, roles.permissionsOf(r.id())))
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

        Map<RoleId, List<String>> activeRolePerms = new LinkedHashMap<>();
        for (Role role : activeRoles) {
            activeRolePerms.put(role.id(), roles.permissionsOf(role.id()));
        }
        Role defaultRole = roles.findDefault();
        List<String> direct = permGrants.stream().map(PermissionGrant::permission).toList();
        EffectivePermissions eff = EffectivePermissions.resolve(
                activeRolePerms, roles.permissionsOf(defaultRole.id()), direct);
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

        return new PlayerPermissionsView(player.value(), roleSummaries, permSummaries, eff.permissions(), display);
    }
}
