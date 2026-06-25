package com.mcplatform.api.rest.support;

import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.protocol.permission.web.RoleWriteRequest;

/**
 * Maps the {@code actor}-free web write DTOs onto the permission domain. The acting admin is NEVER taken
 * from a request body here — it is supplied separately from the JWT principal by the controller
 * (FR-002/FR-020). Response mapping reuses {@link PermissionMapper}.
 */
public final class WebPermissionMapper {

    private WebPermissionMapper() {}

    /** A draft role from a web create request ({@code id} is a placeholder; the DB assigns it). */
    public static Role draft(RoleWriteRequest req) {
        return new Role(RoleId.of(0), req.name(), req.displayName(), req.color(), req.prefix(), req.suffix(),
                req.tabListColor(), req.tabListIcon(), req.displayIcon(), req.weight(), req.teamRank(),
                req.active(), false);
    }

    /** A role carrying the id from the path for an update ({@code isDefault} preserved by the service). */
    public static Role withId(RoleWriteRequest req, long id) {
        return new Role(RoleId.of(id), req.name(), req.displayName(), req.color(), req.prefix(), req.suffix(),
                req.tabListColor(), req.tabListIcon(), req.displayIcon(), req.weight(), req.teamRank(),
                req.active(), false);
    }
}
