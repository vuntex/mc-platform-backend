package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.RoleId;
import java.util.List;

/**
 * Raised when deleting a role that is still inherited by other roles (FR-015). Maps to HTTP 409 — the
 * inheriting roles must drop the edge first. Names the dependent roles so the caller can act.
 */
public final class RoleInheritedException extends RuntimeException {

    public RoleInheritedException(RoleId id, List<String> dependents) {
        super("role " + id.value() + " is inherited by: " + String.join(", ", dependents)
                + " — remove the inheritance there first");
    }
}
