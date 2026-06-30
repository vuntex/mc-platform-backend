package com.mcplatform.application.permission;

import com.mcplatform.application.permission.port.ExpiredGrant;
import com.mcplatform.application.permission.port.GrantAuditPort;
import com.mcplatform.application.permission.port.GrantType;
import com.mcplatform.application.permission.port.PermissionChangePublisher;
import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleAuditPort;
import com.mcplatform.application.permission.port.RoleInheritanceRepository;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.permission.PermissionChangeType;
import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory fakes for the permission application-layer tests (no Spring, no DB). */
final class PermissionFakes {

    private PermissionFakes() {}

    static Role withId(Role r, long id) {
        return new Role(RoleId.of(id), r.name(), r.displayName(), r.color(), r.prefix(), r.suffix(),
                r.tabListColor(), r.tabListIcon(), r.displayIcon(), r.weight(), r.teamRank(), r.active(),
                r.isDefault());
    }

    static Role role(long id, String name, boolean isDefault, boolean active) {
        return new Role(RoleId.of(id), name, name, null, null, null, null, null, null, 0, false, active, isDefault);
    }

    /** Grants explicit permissions per actor; "*" means all. */
    static final class FakeResolver implements PermissionResolver {
        final Map<UUID, Set<String>> granted = new HashMap<>();

        FakeResolver grant(UUID actor, String... perms) {
            granted.computeIfAbsent(actor, k -> new HashSet<>()).addAll(Set.of(perms));
            return this;
        }

        @Override
        public boolean hasPermission(UUID actor, String permission) {
            Set<String> p = granted.getOrDefault(actor, Set.of());
            return p.contains("*") || p.contains(permission);
        }
    }

    static final class FakeRoleRepository implements RoleRepository {
        private final AtomicLong seq = new AtomicLong(0);
        final Map<Long, Role> store = new LinkedHashMap<>();
        final Map<Long, List<String>> perms = new HashMap<>();

        Role seed(Role r) {
            store.put(r.id().value(), r);
            perms.computeIfAbsent(r.id().value(), k -> new ArrayList<>());
            seq.updateAndGet(cur -> Math.max(cur, r.id().value()));
            return r;
        }

        @Override
        public Role create(Role draft, UUID createdBy) {
            Role saved = withId(draft, seq.incrementAndGet());
            store.put(saved.id().value(), saved);
            perms.put(saved.id().value(), new ArrayList<>());
            return saved;
        }

        @Override
        public Role update(Role role) {
            store.put(role.id().value(), role);
            return role;
        }

        @Override
        public void delete(RoleId id) {
            store.remove(id.value());
            perms.remove(id.value());
        }

        @Override
        public Optional<Role> find(RoleId id) {
            return Optional.ofNullable(store.get(id.value()));
        }

        @Override
        public Optional<Role> findByNameIgnoreCase(String name) {
            return store.values().stream().filter(r -> r.name().equalsIgnoreCase(name)).findFirst();
        }

        @Override
        public List<Role> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public Role findDefault() {
            return store.values().stream().filter(Role::isDefault).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no default role seeded"));
        }

        @Override
        public List<Role> findActiveByIds(Collection<RoleId> ids) {
            Set<Long> wanted = new HashSet<>();
            ids.forEach(i -> wanted.add(i.value()));
            return store.values().stream()
                    .filter(r -> wanted.contains(r.id().value()) && r.active())
                    .toList();
        }

        @Override
        public List<String> permissionsOf(RoleId id) {
            return new ArrayList<>(perms.getOrDefault(id.value(), List.of()));
        }

        @Override
        public void addPermission(RoleId id, String permission, UUID addedBy) {
            perms.computeIfAbsent(id.value(), k -> new ArrayList<>());
            if (!perms.get(id.value()).contains(permission)) {
                perms.get(id.value()).add(permission);
            }
        }

        @Override
        public void removePermission(RoleId id, String permission) {
            perms.getOrDefault(id.value(), new ArrayList<>()).remove(permission);
        }
    }

    /** In-memory inheritance edges: child -> set of direct parents. */
    static final class FakeRoleInheritanceRepository implements RoleInheritanceRepository {
        final Map<Long, Set<Long>> parents = new LinkedHashMap<>();

        @Override
        public void add(RoleId child, RoleId parent, UUID actor) {
            parents.computeIfAbsent(child.value(), k -> new java.util.LinkedHashSet<>()).add(parent.value());
        }

        @Override
        public boolean remove(RoleId child, RoleId parent) {
            Set<Long> p = parents.get(child.value());
            return p != null && p.remove(parent.value());
        }

        @Override
        public List<RoleId> directParents(RoleId child) {
            return parents.getOrDefault(child.value(), Set.of()).stream().map(RoleId::of).toList();
        }

        @Override
        public List<RoleId> directChildren(RoleId parent) {
            return parents.entrySet().stream()
                    .filter(e -> e.getValue().contains(parent.value()))
                    .map(e -> RoleId.of(e.getKey()))
                    .toList();
        }

        @Override
        public List<RoleId> dependents(RoleId role) {
            Set<Long> result = new java.util.LinkedHashSet<>();
            java.util.Deque<Long> stack = new java.util.ArrayDeque<>();
            stack.push(role.value());
            Set<Long> visited = new HashSet<>();
            while (!stack.isEmpty()) {
                long cur = stack.pop();
                if (!visited.add(cur)) {
                    continue;
                }
                for (RoleId child : directChildren(RoleId.of(cur))) {
                    if (result.add(child.value())) {
                        stack.push(child.value());
                    }
                }
            }
            return result.stream().map(RoleId::of).toList();
        }
    }

    static final class FakeGrantRepository implements PlayerGrantRepository {
        final Map<String, RoleGrant> roleGrants = new LinkedHashMap<>();
        final Map<String, PermissionGrant> permGrants = new LinkedHashMap<>();

        private static String rk(PlayerId p, RoleId r) {
            return p.value() + "|" + r.value();
        }

        private static String pk(PlayerId p, String perm) {
            return p.value() + "|" + perm;
        }

        @Override
        public void upsertRoleGrant(RoleGrant grant) {
            roleGrants.put(rk(grant.player(), grant.role()), grant);
        }

        @Override
        public boolean revokeRoleGrant(PlayerId player, RoleId role) {
            RoleGrant g = roleGrants.get(rk(player, role));
            if (g == null || !g.active()) {
                return false;
            }
            roleGrants.put(rk(player, role), new RoleGrant(g.player(), g.role(), g.issuedBy(), g.issuedAt(),
                    g.expiresAt(), g.reason(), false));
            return true;
        }

        @Override
        public void upsertPermissionGrant(PermissionGrant grant) {
            permGrants.put(pk(grant.player(), grant.permission()), grant);
        }

        @Override
        public boolean revokePermissionGrant(PlayerId player, String permission) {
            PermissionGrant g = permGrants.get(pk(player, permission));
            if (g == null || !g.active()) {
                return false;
            }
            permGrants.put(pk(player, permission), new PermissionGrant(g.player(), g.permission(),
                    g.issuedBy(), g.issuedAt(), g.expiresAt(), g.reason(), false));
            return true;
        }

        @Override
        public List<RoleGrant> activeRoleGrants(PlayerId player, Instant now) {
            return roleGrants.values().stream()
                    .filter(g -> g.player().equals(player) && g.isActive(now))
                    .toList();
        }

        @Override
        public List<PermissionGrant> activePermissionGrants(PlayerId player, Instant now) {
            return permGrants.values().stream()
                    .filter(g -> g.player().equals(player) && g.isActive(now))
                    .toList();
        }

        @Override
        public List<PlayerId> activeHoldersOf(RoleId role, Instant now) {
            return roleGrants.values().stream()
                    .filter(g -> g.role().equals(role) && g.isActive(now))
                    .map(RoleGrant::player)
                    .distinct()
                    .toList();
        }

        @Override
        public List<ExpiredGrant> findExpired(Instant now) {
            List<ExpiredGrant> out = new ArrayList<>();
            roleGrants.values().stream()
                    .filter(g -> g.active() && g.expiresAt() != null && !g.expiresAt().isAfter(now))
                    .forEach(g -> out.add(ExpiredGrant.role(g.player(), g.role())));
            permGrants.values().stream()
                    .filter(g -> g.active() && g.expiresAt() != null && !g.expiresAt().isAfter(now))
                    .forEach(g -> out.add(ExpiredGrant.permission(g.player(), g.permission())));
            return out;
        }

        @Override
        public void deactivate(ExpiredGrant g) {
            if (g.type() == GrantType.ROLE) {
                RoleGrant rg = roleGrants.get(rk(g.player(), g.role()));
                if (rg != null) {
                    roleGrants.put(rk(g.player(), g.role()), new RoleGrant(rg.player(), rg.role(),
                            rg.issuedBy(), rg.issuedAt(), rg.expiresAt(), rg.reason(), false));
                }
            } else {
                PermissionGrant pg = permGrants.get(pk(g.player(), g.permission()));
                if (pg != null) {
                    permGrants.put(pk(g.player(), g.permission()), new PermissionGrant(pg.player(),
                            pg.permission(), pg.issuedBy(), pg.issuedAt(), pg.expiresAt(), pg.reason(), false));
                }
            }
        }
    }

    static final class FakeAudit implements GrantAuditPort {
        final List<Entry> entries = new ArrayList<>();

        @Override
        public void record(Entry entry) {
            entries.add(entry);
        }

        long count(Action action) {
            return entries.stream().filter(e -> e.action() == action).count();
        }
    }

    static final class FakeRoleAudit implements RoleAuditPort {
        final List<Action> actions = new ArrayList<>();
        final List<String> permissions = new ArrayList<>();
        final List<UUID> actors = new ArrayList<>();

        @Override
        public void record(Action action, RoleId role, String roleName, String permission, UUID actor,
                Instant at) {
            actions.add(action);
            permissions.add(permission);
            actors.add(actor);
        }

        long count(Action action) {
            return actions.stream().filter(a -> a == action).count();
        }
    }

    static final class FakePublisher implements PermissionChangePublisher {
        final List<Map.Entry<UUID, PermissionChangeType>> events = new ArrayList<>();

        @Override
        public void publish(UUID player, PermissionChangeType type) {
            events.add(Map.entry(player, type));
        }

        long countOfType(PermissionChangeType type) {
            return events.stream().filter(e -> e.getValue() == type).count();
        }
    }
}
