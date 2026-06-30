package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.RoleId;

/**
 * Raised when setting an inheritance edge {@code child -> parent} would create a cycle (direct
 * self-reference, or {@code parent} already inherits {@code child} transitively). Maps to HTTP 409
 * (FR-010). The pre-check happens before any write, so the inheritance graph stays unchanged.
 */
public final class RoleInheritanceCycleException extends RuntimeException {

    public RoleInheritanceCycleException(RoleId child, RoleId parent) {
        super("inheritance " + child.value() + " -> " + parent.value() + " would create a cycle");
    }
}
