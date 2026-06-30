package com.mcplatform.protocol.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.protocol.core.HttpMethod;
import com.mcplatform.protocol.permission.web.InheritanceWriteRequest;
import org.junit.jupiter.api.Test;

/** Contract test (JDK-pure) for the inheritance endpoint descriptors and the write DTO. */
class PermissionEndpointsInheritanceTest {

    @Test
    void listInheritanceDescriptor() {
        assertThat(PermissionEndpoints.LIST_INHERITANCE.method()).isEqualTo(HttpMethod.GET);
        assertThat(PermissionEndpoints.LIST_INHERITANCE.pathTemplate()).isEqualTo("/api/permission/roles/{id}/inheritance");
        assertThat(PermissionEndpoints.LIST_INHERITANCE.responseType()).isEqualTo(Long[].class);
    }

    @Test
    void addInheritanceDescriptor() {
        assertThat(PermissionEndpoints.ADD_INHERITANCE.method()).isEqualTo(HttpMethod.POST);
        assertThat(PermissionEndpoints.ADD_INHERITANCE.pathTemplate()).isEqualTo("/api/permission/roles/{id}/inheritance");
        assertThat(PermissionEndpoints.ADD_INHERITANCE.requestType()).isEqualTo(InheritanceWriteRequest.class);
        assertThat(PermissionEndpoints.ADD_INHERITANCE.responseType()).isEqualTo(RoleResponse.class);
    }

    @Test
    void removeInheritanceDescriptor() {
        assertThat(PermissionEndpoints.REMOVE_INHERITANCE.method()).isEqualTo(HttpMethod.DELETE);
        assertThat(PermissionEndpoints.REMOVE_INHERITANCE.pathTemplate())
                .isEqualTo("/api/permission/roles/{id}/inheritance/{parentId}");
        assertThat(PermissionEndpoints.REMOVE_INHERITANCE.responseType()).isEqualTo(RoleResponse.class);
    }

    @Test
    void writeRequestCarriesOnlyParentId() {
        assertThat(new InheritanceWriteRequest(42L).parentRoleId()).isEqualTo(42L);
    }
}
