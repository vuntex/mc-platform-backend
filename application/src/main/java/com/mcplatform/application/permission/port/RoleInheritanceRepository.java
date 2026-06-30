package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.RoleId;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port for the {@code role_inheritance} edges (state-stored CRUD). An edge means
 * "{@code role} inherits the permissions of {@code parent}". Transitivity is computed in the domain
 * ({@code RoleHierarchy}) from {@link #directParents}, so this port only exposes direct edges plus the
 * one query that benefits from a single recursive SQL: {@link #dependents}.
 */
public interface RoleInheritanceRepository {

    /** Adds the edge {@code child -> parent} (idempotent: re-adding an existing edge is a no-op, FR-014). */
    void add(RoleId child, RoleId parent, UUID actor);

    /** Removes the edge {@code child -> parent}. Returns true iff an edge existed (idempotent, FR-014). */
    boolean remove(RoleId child, RoleId parent);

    /** The direct parents (inherited roles) of {@code child}. */
    List<RoleId> directParents(RoleId child);

    /** The direct children — roles that directly inherit {@code parent} (for the delete-409 message, FR-015). */
    List<RoleId> directChildren(RoleId parent);

    /** Transitive children — all roles that inherit {@code role} directly or indirectly (live-push fan-out). */
    List<RoleId> dependents(RoleId role);
}
