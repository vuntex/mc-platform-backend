package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.PLAYER_ROLE_GRANT;
import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.permission.GrantRoleRequest;
import com.mcplatform.protocol.permission.PermissionChangedEvent;
import com.mcplatform.protocol.permission.PermissionChangedEventCodec;
import com.mcplatform.protocol.permission.PermissionChannels;
import com.mcplatform.protocol.permission.PlayerPermissionsResponse;
import com.mcplatform.protocol.permission.RolePermissionRequest;
import com.mcplatform.protocol.permission.RoleRequest;
import com.mcplatform.protocol.permission.RoleResponse;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end vertical slice for permissions: REST → service (backend-authoritative gate) → jOOQ →
 * Redis Pub/Sub. Covers role create + permission config + grant → effective resolution, the 403 path,
 * and the live {@code mc:permission:changed} event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PermissionVerticalSliceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mcplatform.redis.host", REDIS::getHost);
        registry.add("mcplatform.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    RedisCacheAdapter redis;

    @Autowired
    DSLContext dsl;

    private UUID staff(String role) {
        UUID uuid = UUID.randomUUID();
        long roleId = dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq(role)).fetchOne(ROLE.ID);
        dsl.insertInto(PLAYER_ROLE_GRANT)
                .set(PLAYER_ROLE_GRANT.UUID, uuid)
                .set(PLAYER_ROLE_GRANT.ROLE_ID, roleId)
                .set(PLAYER_ROLE_GRANT.ISSUED_BY, uuid)
                .set(PLAYER_ROLE_GRANT.ACTIVE, true)
                .execute();
        return uuid;
    }

    private RoleRequest roleReq(String name, String displayIcon, UUID actor) {
        return new RoleRequest(name, name, null, null, null, null, null, displayIcon, 10, false, true, actor);
    }

    @Test
    void createConfigureGrantResolvesAndPublishes() throws Exception {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID();

        RoleResponse role = rest.postForObject("/api/permission/roles",
                roleReq("Premium", "material:DIAMOND_SWORD", admin), RoleResponse.class);
        assertThat(role.id()).isPositive();
        assertThat(role.displayIcon()).isEqualTo("material:DIAMOND_SWORD"); // opaque, stored + echoed

        rest.postForObject("/api/permission/roles/" + role.id() + "/permissions",
                new RolePermissionRequest("home.set", admin), RoleResponse.class);

        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (AutoCloseable subscription = redis.subscribe(PermissionChannels.CHANGED, events::offer)) {
            Thread.sleep(200);

            PlayerPermissionsResponse view = rest.postForObject(
                    "/api/permission/players/" + player + "/roles",
                    new GrantRoleRequest(role.id(), null, "vip purchase", admin),
                    PlayerPermissionsResponse.class);
            assertThat(view.effectivePermissions()).contains("home.set");
            assertThat(view.roles()).extracting(g -> g.label()).contains("Premium");
            assertThat(view.display().displayIcon()).isEqualTo("material:DIAMOND_SWORD"); // icon flows to display

            String wire = events.poll(3, TimeUnit.SECONDS);
            assertThat(wire).as("a permission-changed event was published").isNotNull();
            PermissionChangedEvent published =
                    PlatformProtocol.create().decode(wire, PermissionChangedEventCodec.INSTANCE);
            assertThat(published.playerUuid()).isEqualTo(player);
            assertThat(published.changeType()).isEqualTo("GRANT_ADDED");
        }
    }

    @Test
    void createRoleForbiddenWithoutPermission() {
        UUID nobody = UUID.randomUUID(); // no grant -> default role (empty) -> not allowed
        ResponseEntity<String> denied = rest.postForEntity(
                "/api/permission/roles", roleReq("Sneaky", null, nobody), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void effectiveForUngrantedPlayerUsesDefaultDisplay() {
        UUID player = UUID.randomUUID();
        PlayerPermissionsResponse view = rest.getForObject(
                "/api/permission/players/" + player + "/effective", PlayerPermissionsResponse.class);
        assertThat(view.display().displayName()).isEqualTo("Default");
        assertThat(view.roles()).isEmpty();
    }
}
