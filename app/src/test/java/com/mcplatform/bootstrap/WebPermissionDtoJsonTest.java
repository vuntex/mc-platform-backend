package com.mcplatform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcplatform.protocol.permission.web.GrantPermissionWriteRequest;
import com.mcplatform.protocol.permission.web.GrantRoleWriteRequest;
import com.mcplatform.protocol.permission.web.RolePermissionWriteRequest;
import com.mcplatform.protocol.permission.web.RevokePermissionWriteRequest;
import com.mcplatform.protocol.permission.web.RoleWriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * JSON contract for the {@code actor}-free web write DTOs. The critical invariant (FR-002/FR-020): none of
 * these carries an {@code actor} field, so the acting admin cannot be supplied via the body — it is taken
 * exclusively from the JWT. Jackson (de)serialization to the documented field names is verified with
 * Spring's real ObjectMapper ({@code @JsonTest}; no DB/Redis).
 */
@JsonTest
class WebPermissionDtoJsonTest {

    @Autowired
    ObjectMapper json;

    @Test
    void roleWriteRequestHasNoActorField() throws Exception {
        RoleWriteRequest dto = new RoleWriteRequest("Premium", "Premium", null, null, null, null, null,
                "material:DIAMOND_SWORD", 10, false, true);
        assertThat(json.readTree(json.writeValueAsString(dto)).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("name", "displayName", "color", "prefix", "suffix",
                        "tabListColor", "tabListIcon", "displayIcon", "weight", "teamRank", "active")
                .doesNotContain("actor");
    }

    @Test
    void grantRoleWriteRequestHasNoActorField() throws Exception {
        GrantRoleWriteRequest parsed = json.readValue(
                "{\"roleId\":2,\"expiresInSeconds\":604800,\"reason\":\"trial\"}", GrantRoleWriteRequest.class);
        assertThat(parsed).isEqualTo(new GrantRoleWriteRequest(2L, 604800L, "trial"));
        assertThat(json.readTree(json.writeValueAsString(parsed)).fieldNames()).toIterable()
                .doesNotContain("actor");
    }

    @Test
    void grantPermissionWriteRequestHasNoActorField() throws Exception {
        GrantPermissionWriteRequest parsed = json.readValue(
                "{\"permission\":\"home.set\",\"expiresInSeconds\":null,\"reason\":null}",
                GrantPermissionWriteRequest.class);
        assertThat(parsed).isEqualTo(new GrantPermissionWriteRequest("home.set", null, null));
        assertThat(json.readTree(json.writeValueAsString(parsed)).fieldNames()).toIterable()
                .doesNotContain("actor");
    }

    @Test
    void rolePermissionAndRevokeRequestsHaveNoActorField() throws Exception {
        assertThat(json.readTree(json.writeValueAsString(new RolePermissionWriteRequest("home.set"))).fieldNames())
                .toIterable().containsExactly("permission").doesNotContain("actor");
        assertThat(json.readTree(json.writeValueAsString(new RevokePermissionWriteRequest("home.set", "x")))
                .fieldNames()).toIterable().containsExactlyInAnyOrder("permission", "reason").doesNotContain("actor");
    }
}
