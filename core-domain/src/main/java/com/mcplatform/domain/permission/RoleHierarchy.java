package com.mcplatform.domain.permission;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure transitive role-inheritance logic — no DB, no framework. A role inherits ONLY the permissions of
 * the roles in its (transitive) inheritance list (FR-002/FR-003); the union is purely additive, order-
 * and weight-independent (FR-004). All traversals carry a visited-set, so a residual cycle in the data
 * still terminates (FR-010a) — the write-time {@link #wouldCreateCycle} check is the primary guard
 * (FR-010), this is the defensive net.
 *
 * <p>Callers supply the graph as functions ({@code directParentsOf}, {@code permissionsOf}) so this class
 * stays storage-agnostic and unit-testable. It mirrors — in lockstep — the recursive SQL in the jOOQ
 * resolver (the authoritative {@code hasPermission} path).
 */
public final class RoleHierarchy {

    private RoleHierarchy() {}

    /** A permission's origin in an effective view: whether it is held directly and which roles supply it. */
    public record Provenance(boolean own, Set<RoleId> sources) {
    }

    /**
     * The transitive closure of {@code startRoles} over the inheritance edges (each role's direct parents).
     * Cycle-safe via a visited-set; the {@code active} flag of parents is intentionally NOT consulted here
     * (it is not inherited, FR-016) — callers pre-filter the {@code startRoles} to active direct roles.
     */
    public static Set<RoleId> reachable(Set<RoleId> startRoles, Function<RoleId, List<RoleId>> directParentsOf) {
        Set<RoleId> visited = new LinkedHashSet<>();
        Deque<RoleId> stack = new ArrayDeque<>(startRoles);
        while (!stack.isEmpty()) {
            RoleId role = stack.pop();
            if (!visited.add(role)) {
                continue; // already expanded → skip (terminates even on a residual cycle)
            }
            for (RoleId parent : directParentsOf.apply(role)) {
                if (!visited.contains(parent)) {
                    stack.push(parent);
                }
            }
        }
        return visited;
    }

    /**
     * Whether adding the edge {@code child -> parent} would create a cycle: a direct self-reference, or
     * {@code parent} already (transitively) inherits {@code child}. Evaluated against the CURRENT graph
     * (before the edge is added).
     */
    public static boolean wouldCreateCycle(RoleId child, RoleId parent,
            Function<RoleId, List<RoleId>> directParentsOf) {
        if (child.equals(parent)) {
            return true;
        }
        return reachable(Set.of(parent), directParentsOf).contains(child);
    }

    /**
     * Per-permission provenance for an effective view (FR-022a). {@code ownPermissions} are flagged
     * {@code own=true}; every reachable role from {@code contributingRoles} whose configured permissions
     * contain a string is recorded as a source. A permission appears once, with the FULL set of source
     * roles (no arbitrary "nearest"); order-independent.
     */
    public static Map<String, Provenance> resolveWithProvenance(
            Set<RoleId> contributingRoles,
            Function<RoleId, List<RoleId>> directParentsOf,
            Function<RoleId, List<String>> permissionsOf,
            Collection<String> ownPermissions) {

        Map<String, Boolean> own = new LinkedHashMap<>();
        Map<String, Set<RoleId>> sources = new LinkedHashMap<>();

        for (String p : ownPermissions) {
            own.put(p, Boolean.TRUE);
            sources.computeIfAbsent(p, k -> new LinkedHashSet<>());
        }
        for (RoleId role : reachable(contributingRoles, directParentsOf)) {
            for (String p : permissionsOf.apply(role)) {
                own.putIfAbsent(p, Boolean.FALSE);
                sources.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(role);
            }
        }

        Map<String, Provenance> result = new LinkedHashMap<>();
        for (String p : own.keySet()) {
            result.put(p, new Provenance(own.get(p), Set.copyOf(sources.get(p))));
        }
        return result;
    }
}
