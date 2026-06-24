package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.GRANT_AUDIT;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_ROLE_GRANT;
import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static com.mcplatform.persistence.jooq.Tables.ROLE_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.bootstrap.adapter.JwtTokenService;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.permission.PlayerPermissionsResponse;
import com.mcplatform.protocol.permission.RoleResponse;
import com.mcplatform.protocol.permission.web.GrantRoleWriteRequest;
import com.mcplatform.protocol.permission.web.RolePermissionWriteRequest;
import com.mcplatform.protocol.permission.web.RevokePermissionWriteRequest;
import com.mcplatform.protocol.permission.web.RoleWriteRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * End-to-end vertical slice for the JWT-gated web rank-management surface ({@code /api/web/permission/**}):
 * REST → Spring Security (slice 4 JWT) → reused 002 use cases → jOOQ → Redis Pub/Sub. Covers all three
 * user stories: role CRUD (US1), role-permission config (US2) and player grants (US3), incl. 401/403/404/
 * 409/422, cascade delete, the {@code role_audit} trail, issued_by-from-token, grant to a never-joined
 * UUID, and the live {@code mc:permission:changed} event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WebPermissionVerticalSliceTest {

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

    @Value("${mcplatform.webauth.jwt.secret}")
    String jwtSecret;

    // --- helpers ----------------------------------------------------------

    /** A staff member holding {@code role}, returned as a freshly minted access token (Bearer). */
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

    private String token(UUID uuid) {
        return new JwtTokenService(jwtSecret).issue(PlayerId.of(uuid), Duration.ofMinutes(15), Instant.now());
    }

    private <T> HttpEntity<T> auth(UUID actor, T body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token(actor));
        return new HttpEntity<>(body, h);
    }

    private long defaultRoleId() {
        return dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("DEFAULT")).fetchOne(ROLE.ID);
    }

    private RoleWriteRequest role(String name) {
        return new RoleWriteRequest(name, name, null, null, null, null, null, null, 10, false, true);
    }

    // ====================== US1 — roles ===================================

    @Test
    void readRequiresJwt() {
        ResponseEntity<String> r = rest.getForEntity("/api/web/permission/roles", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createRoleForbiddenForModerator() {
        UUID mod = staff("MODERATOR"); // has no permission.role.create
        ResponseEntity<String> r = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(mod, role("Sneaky")), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCreatesUpdatesAndListsRole() {
        UUID admin = staff("ADMIN");
        RoleResponse created = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("Premium")), RoleResponse.class).getBody();
        assertThat(created.id()).isPositive();

        RoleWriteRequest edit = new RoleWriteRequest("Premium", "Premium+", null, null, null, null, null,
                null, 25, false, true);
        RoleResponse updated = rest.exchange("/api/web/permission/roles/" + created.id(), HttpMethod.PUT,
                auth(admin, edit), RoleResponse.class).getBody();
        assertThat(updated.displayName()).isEqualTo("Premium+");
        assertThat(updated.weight()).isEqualTo(25);

        ResponseEntity<RoleResponse[]> all = rest.exchange("/api/web/permission/roles", HttpMethod.GET,
                auth(admin, null), RoleResponse[].class);
        assertThat(all.getBody()).extracting(RoleResponse::name).contains("Premium");
    }

    @Test
    void defaultRoleCannotBeDeleted() {
        UUID admin = staff("ADMIN");
        ResponseEntity<String> r = rest.exchange("/api/web/permission/roles/" + defaultRoleId(),
                HttpMethod.DELETE, auth(admin, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("default_role_protected");
    }

    @Test
    void duplicateRoleNameConflicts() {
        UUID admin = staff("ADMIN");
        rest.exchange("/api/web/permission/roles", HttpMethod.POST, auth(admin, role("Unique")), RoleResponse.class);
        ResponseEntity<String> dup = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("unique")), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).contains("role_name_conflict");
    }

    @Test
    void unknownRoleIsNotFound() {
        UUID admin = staff("ADMIN");
        ResponseEntity<String> r = rest.exchange("/api/web/permission/roles/999999", HttpMethod.GET,
                auth(admin, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRoleWithMembersCascadesAndAudits() {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID();
        RoleResponse yt = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("YouTuber")), RoleResponse.class).getBody();
        rest.exchange("/api/web/permission/players/" + player + "/roles", HttpMethod.POST,
                auth(admin, new GrantRoleWriteRequest(yt.id(), null, "vip")), PlayerPermissionsResponse.class);

        ResponseEntity<Void> del = rest.exchange("/api/web/permission/roles/" + yt.id(), HttpMethod.DELETE,
                auth(admin, null), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        PlayerPermissionsResponse view = rest.exchange("/api/web/permission/players/" + player + "/effective",
                HttpMethod.GET, auth(admin, null), PlayerPermissionsResponse.class).getBody();
        assertThat(view.roles()).extracting(g -> g.label()).doesNotContain("YouTuber");

        assertThat(dsl.fetchCount(ROLE_AUDIT, ROLE_AUDIT.ROLE_ID.eq(yt.id()).and(ROLE_AUDIT.ACTION.eq("ROLE_DELETE"))))
                .isEqualTo(1);
        assertThat(dsl.fetchCount(GRANT_AUDIT, GRANT_AUDIT.PLAYER_UUID.eq(player).and(GRANT_AUDIT.ACTION.eq("REVOKE"))))
                .isEqualTo(1);
    }

    // ====================== US2 — role permissions ========================

    @Test
    void addRolePermissionForbiddenForModerator() {
        UUID admin = staff("ADMIN");
        UUID mod = staff("MODERATOR");
        RoleResponse r = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("Helper")), RoleResponse.class).getBody();
        ResponseEntity<String> denied = rest.exchange("/api/web/permission/roles/" + r.id() + "/permissions",
                HttpMethod.POST, auth(mod, new RolePermissionWriteRequest("home.set")), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void addRolePermissionPublishesToHoldersAndAudits() throws Exception {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID();
        RoleResponse supporter = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("Supporter")), RoleResponse.class).getBody();
        rest.exchange("/api/web/permission/players/" + player + "/roles", HttpMethod.POST,
                auth(admin, new GrantRoleWriteRequest(supporter.id(), null, null)), PlayerPermissionsResponse.class);

        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (AutoCloseable sub = redis.subscribe(
                com.mcplatform.protocol.permission.PermissionChannels.CHANGED, events::offer)) {
            Thread.sleep(200);
            RoleResponse afterAdd = rest.exchange("/api/web/permission/roles/" + supporter.id() + "/permissions",
                    HttpMethod.POST, auth(admin, new RolePermissionWriteRequest("home.set")), RoleResponse.class)
                    .getBody();
            assertThat(afterAdd.permissions()).contains("home.set");

            String wire = events.poll(3, TimeUnit.SECONDS);
            assertThat(wire).as("role-config change published to the holder").isNotNull();
            assertThat(wire).contains("ROLE_CONFIG_CHANGED");
        }

        assertThat(dsl.fetchCount(ROLE_AUDIT, ROLE_AUDIT.ROLE_ID.eq(supporter.id())
                .and(ROLE_AUDIT.ACTION.eq("ROLE_PERMISSION_ADD")))).isEqualTo(1);
    }

    @Test
    void invalidPermissionSyntaxIsUnprocessable() {
        UUID admin = staff("ADMIN");
        RoleResponse r = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("Builder")), RoleResponse.class).getBody();
        ResponseEntity<String> bad = rest.exchange("/api/web/permission/roles/" + r.id() + "/permissions",
                HttpMethod.POST, auth(admin, new RolePermissionWriteRequest("not valid")), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(bad.getBody()).contains("permission_invalid");
    }

    // ====================== US3 — grants ==================================

    @Test
    void grantRoleForbiddenForModerator() {
        UUID admin = staff("ADMIN");
        UUID mod = staff("MODERATOR");
        RoleResponse r = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("VIP")), RoleResponse.class).getBody();
        ResponseEntity<String> denied = rest.exchange(
                "/api/web/permission/players/" + UUID.randomUUID() + "/roles", HttpMethod.POST,
                auth(mod, new GrantRoleWriteRequest(r.id(), null, null)), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void grantRoleSetsIssuedByFromTokenNotBody() {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID();
        RoleResponse r = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("Elite")), RoleResponse.class).getBody();

        PlayerPermissionsResponse view = rest.exchange("/api/web/permission/players/" + player + "/roles",
                HttpMethod.POST, auth(admin, new GrantRoleWriteRequest(r.id(), null, "earned")),
                PlayerPermissionsResponse.class).getBody();

        assertThat(view.roles()).anySatisfy(g -> {
            assertThat(g.label()).isEqualTo("Elite");
            assertThat(g.issuedBy()).isEqualTo(admin); // from the JWT, never from the body
        });
    }

    @Test
    void grantWithExpiryInThePastIsUnprocessable() {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID();
        RoleResponse r = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("Trial")), RoleResponse.class).getBody();
        ResponseEntity<String> bad = rest.exchange("/api/web/permission/players/" + player + "/roles",
                HttpMethod.POST, auth(admin, new GrantRoleWriteRequest(r.id(), -3600L, null)), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void grantToNeverJoinedPlayerIsAccepted() {
        UUID admin = staff("ADMIN");
        UUID neverJoined = UUID.randomUUID(); // no player row exists
        RoleResponse r = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("Founder")), RoleResponse.class).getBody();

        PlayerPermissionsResponse view = rest.exchange(
                "/api/web/permission/players/" + neverJoined + "/roles", HttpMethod.POST,
                auth(admin, new GrantRoleWriteRequest(r.id(), null, null)), PlayerPermissionsResponse.class)
                .getBody();
        assertThat(view.roles()).extracting(g -> g.label()).contains("Founder");
    }

    @Test
    void revokeNonExistentGrantIsIdempotent() {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID();
        ResponseEntity<PlayerPermissionsResponse> r = rest.exchange(
                "/api/web/permission/players/" + player + "/permissions", HttpMethod.DELETE,
                auth(admin, new RevokePermissionWriteRequest("home.set", null)), PlayerPermissionsResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
