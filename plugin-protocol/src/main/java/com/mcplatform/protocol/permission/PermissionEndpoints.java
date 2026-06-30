package com.mcplatform.protocol.permission;

import com.mcplatform.protocol.core.EndpointDescriptor;
import com.mcplatform.protocol.core.HttpMethod;
import com.mcplatform.protocol.permission.web.InheritanceWriteRequest;

/**
 * The permission/rank REST endpoints as constants, so clients reference them by name instead of
 * hard-coding paths. Roles are a flat {@code /api/permission/roles} resource; player grants live under
 * {@code /api/permission/players/{uuid}}. All mutating calls carry the {@code actor} in their body
 * (revoke-by-path carries it as a query parameter client-side).
 */
public final class PermissionEndpoints {

    public static final EndpointDescriptor<Void, RoleResponse[]> LIST_ROLES =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/permission/roles", Void.class, RoleResponse[].class);

    public static final EndpointDescriptor<RoleRequest, RoleResponse> CREATE_ROLE =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/permission/roles", RoleRequest.class, RoleResponse.class);

    public static final EndpointDescriptor<RoleRequest, RoleResponse> UPDATE_ROLE =
            new EndpointDescriptor<>(HttpMethod.PUT, "/api/permission/roles/{id}", RoleRequest.class, RoleResponse.class);

    public static final EndpointDescriptor<Void, Void> DELETE_ROLE =
            new EndpointDescriptor<>(HttpMethod.DELETE, "/api/permission/roles/{id}", Void.class, Void.class);

    public static final EndpointDescriptor<RolePermissionRequest, RoleResponse> ADD_ROLE_PERMISSION =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/permission/roles/{id}/permissions",
                    RolePermissionRequest.class, RoleResponse.class);

    public static final EndpointDescriptor<RolePermissionRequest, RoleResponse> REMOVE_ROLE_PERMISSION =
            new EndpointDescriptor<>(HttpMethod.DELETE, "/api/permission/roles/{id}/permissions",
                    RolePermissionRequest.class, RoleResponse.class);

    public static final EndpointDescriptor<GrantRoleRequest, PlayerPermissionsResponse> GRANT_ROLE =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/permission/players/{uuid}/roles",
                    GrantRoleRequest.class, PlayerPermissionsResponse.class);

    public static final EndpointDescriptor<Void, PlayerPermissionsResponse> REVOKE_ROLE =
            new EndpointDescriptor<>(HttpMethod.DELETE, "/api/permission/players/{uuid}/roles/{roleId}",
                    Void.class, PlayerPermissionsResponse.class);

    public static final EndpointDescriptor<GrantPermissionRequest, PlayerPermissionsResponse> GRANT_PERMISSION =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/permission/players/{uuid}/permissions",
                    GrantPermissionRequest.class, PlayerPermissionsResponse.class);

    public static final EndpointDescriptor<RevokePermissionRequest, PlayerPermissionsResponse> REVOKE_PERMISSION =
            new EndpointDescriptor<>(HttpMethod.DELETE, "/api/permission/players/{uuid}/permissions",
                    RevokePermissionRequest.class, PlayerPermissionsResponse.class);

    public static final EndpointDescriptor<Void, PlayerPermissionsResponse> EFFECTIVE =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/permission/players/{uuid}/effective",
                    Void.class, PlayerPermissionsResponse.class);

    public static final EndpointDescriptor<Void, Long[]> LIST_INHERITANCE =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/permission/roles/{id}/inheritance",
                    Void.class, Long[].class);

    public static final EndpointDescriptor<InheritanceWriteRequest, RoleResponse> ADD_INHERITANCE =
            new EndpointDescriptor<>(HttpMethod.POST, "/api/permission/roles/{id}/inheritance",
                    InheritanceWriteRequest.class, RoleResponse.class);

    public static final EndpointDescriptor<Void, RoleResponse> REMOVE_INHERITANCE =
            new EndpointDescriptor<>(HttpMethod.DELETE, "/api/permission/roles/{id}/inheritance/{parentId}",
                    Void.class, RoleResponse.class);

    private PermissionEndpoints() {}
}
