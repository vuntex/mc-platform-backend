package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.GRANT_AUDIT;
import static com.mcplatform.persistence.jooq.Tables.PLAYER;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_ROLE_GRANT;
import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static com.mcplatform.persistence.jooq.Tables.ROLE_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.bootstrap.adapter.JwtTokenService;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.permission.PermissionCatalogResponse;
import com.mcplatform.protocol.permission.PlayerPermissionsResponse;
import com.mcplatform.protocol.permission.RoleResponse;
import com.mcplatform.protocol.permission.web.GrantPermissionWriteRequest;
import com.mcplatform.protocol.permission.web.GrantRoleWriteRequest;
import com.mcplatform.protocol.permission.web.InheritanceWriteRequest;
import com.mcplatform.protocol.permission.web.RolePermissionWriteRequest;
import com.mcplatform.protocol.permission.web.RevokePermissionWriteRequest;
import com.mcplatform.protocol.permission.web.RoleWriteRequest;
import com.mcplatform.protocol.player.PlayerSummary;
import com.mcplatform.protocol.webauth.MeResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    // ====================== /api/web/players/search =======================

    private UUID playerNamed(String name) {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, uuid)
                .set(PLAYER.NAME, name)
                .set(PLAYER.NAME_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
        return uuid;
    }

    @Test
    void playerSearchReturnsCaseInsensitivePrefixMatches() {
        UUID admin = staff("ADMIN");
        UUID notch = playerNamed("Notch");
        playerNamed("Notchy");
        playerNamed("Herobrine");

        PlayerSummary[] hits = rest.exchange("/api/web/players/search?name=not", HttpMethod.GET,
                auth(admin, null), PlayerSummary[].class).getBody();
        assertThat(hits).extracting(PlayerSummary::name).containsExactlyInAnyOrder("Notch", "Notchy");
        assertThat(hits).extracting(PlayerSummary::uuid).contains(notch);

        PlayerSummary[] none = rest.exchange("/api/web/players/search?name=zzz", HttpMethod.GET,
                auth(admin, null), PlayerSummary[].class).getBody();
        assertThat(none).isEmpty();
    }

    @Test
    void playerSearchForbiddenWithoutReadPermission() {
        UUID mod = staff("MODERATOR"); // no permission.read
        playerNamed("Target");
        ResponseEntity<String> r = rest.exchange("/api/web/players/search?name=tar", HttpMethod.GET,
                auth(mod, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void playerSearchRequiresJwt() {
        assertThat(rest.getForEntity("/api/web/players/search?name=x", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ====================== /api/web/me — identity ========================

    @Test
    void meReturnsUuidAndCachedNameFromToken() {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, uuid)
                .set(PLAYER.NAME, "Vuntex")
                .set(PLAYER.NAME_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();

        MeResponse me = rest.exchange("/api/web/me", HttpMethod.GET, auth(uuid, null), MeResponse.class).getBody();
        assertThat(me.uuid()).isEqualTo(uuid);
        assertThat(me.name()).isEqualTo("Vuntex"); // backend-authoritative, not the typed login name
        assertThat(me.permissions()).isNotNull().isEmpty(); // bare player → default role (no perms)
        assertThat(me.primaryRole()).isNotNull();
        assertThat(me.primaryRole().name()).isEqualTo("DEFAULT"); // no active rank → default-role fallback
        assertThat(me.primaryRole().weight()).isZero();
    }

    @Test
    void meExposesCallersOwnEffectivePermissionsForButtonGating() {
        UUID admin = staff("ADMIN"); // ADMIN role carries "*"
        MeResponse me = rest.exchange("/api/web/me", HttpMethod.GET, auth(admin, null), MeResponse.class).getBody();
        assertThat(me.uuid()).isEqualTo(admin);
        assertThat(me.permissions()).contains("*"); // client applies the wildcard rule to gate buttons
        assertThat(me.primaryRole().name()).isEqualTo("ADMIN"); // highest-priority active rank
        assertThat(me.primaryRole().displayName()).isEqualTo("Admin");
        assertThat(me.primaryRole().weight()).isEqualTo(100);
    }

    @Test
    void meRequiresJwt() {
        assertThat(rest.getForEntity("/api/web/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ====================== permission catalog ============================

    @Test
    void catalogListsWebPermissionsGroupedByThemeForAdmin() {
        UUID admin = staff("ADMIN");
        PermissionCatalogResponse catalog = rest.exchange("/api/web/permission/catalog", HttpMethod.GET,
                auth(admin, null), PermissionCatalogResponse.class).getBody();

        assertThat(catalog.groups()).extracting(g -> g.displayName())
                .containsExactly("Economy", "Rollen & Permissions");
        assertThat(catalog.groups()).allSatisfy(g -> assertThat(g.description()).isNotBlank());
        assertThat(catalog.groups()).flatExtracting(g -> g.permissions()).extracting(p -> p.key())
                .contains("permission.economy.read", "permission.role.create", "permission.read");
        assertThat(catalog.groups()).flatExtracting(g -> g.permissions())
                .allSatisfy(p -> assertThat(p.description()).isNotBlank());
    }

    @Test
    void catalogForbiddenWithoutReadPermission() {
        UUID mod = staff("MODERATOR"); // no permission.read
        ResponseEntity<String> r = rest.exchange("/api/web/permission/catalog", HttpMethod.GET,
                auth(mod, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void catalogRequiresJwt() {
        assertThat(rest.getForEntity("/api/web/permission/catalog", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
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

    @Test
    void defaultRoleIsShownWhenPlayerHasNoOtherRole() {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID(); // no grants at all
        PlayerPermissionsResponse view = rest.exchange("/api/web/permission/players/" + player + "/effective",
                HttpMethod.GET, auth(admin, null), PlayerPermissionsResponse.class).getBody();
        assertThat(view.roles()).extracting(g -> g.label()).containsExactly("DEFAULT");
        assertThat(view.roles().get(0).issuedBy()).isNull(); // synthetic fallback, no issuer
    }

    @Test
    void revokingTheLastRoleFallsBackToDefaultInTheView() {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID();
        RoleResponse temp = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("TempRank")), RoleResponse.class).getBody();
        rest.exchange("/api/web/permission/players/" + player + "/roles", HttpMethod.POST,
                auth(admin, new GrantRoleWriteRequest(temp.id(), null, null)), PlayerPermissionsResponse.class);

        PlayerPermissionsResponse afterRevoke = rest.exchange(
                "/api/web/permission/players/" + player + "/roles/" + temp.id(), HttpMethod.DELETE,
                auth(admin, null), PlayerPermissionsResponse.class).getBody();
        assertThat(afterRevoke.roles()).extracting(g -> g.label()).containsExactly("DEFAULT");
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
    void defaultRoleCannotBeGrantedViaWeb() {
        UUID admin = staff("ADMIN");
        UUID player = UUID.randomUUID();
        ResponseEntity<String> r = rest.exchange("/api/web/permission/players/" + player + "/roles",
                HttpMethod.POST, auth(admin, new GrantRoleWriteRequest(defaultRoleId(), null, null)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("default_role_protected");
    }

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
    void effectiveResolvesIssuerUuidToDisplayName() {
        UUID admin = staff("ADMIN");
        // give the acting admin a player row so the issuer UUID resolves to a name
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, admin)
                .set(PLAYER.NAME, "AdminGuy")
                .set(PLAYER.NAME_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
        UUID player = UUID.randomUUID();
        RoleResponse issued = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, role("IssuerRole")), RoleResponse.class).getBody();
        rest.exchange("/api/web/permission/players/" + player + "/roles", HttpMethod.POST,
                auth(admin, new GrantRoleWriteRequest(issued.id(), null, "earned")), PlayerPermissionsResponse.class);

        PlayerPermissionsResponse view = rest.exchange("/api/web/permission/players/" + player + "/effective",
                HttpMethod.GET, auth(admin, null), PlayerPermissionsResponse.class).getBody();

        assertThat(view.roles()).anySatisfy(g -> {
            assertThat(g.label()).isEqualTo("IssuerRole");
            assertThat(g.issuedBy()).isEqualTo(admin);
            assertThat(g.issuedByName()).isEqualTo("AdminGuy"); // name, not just the UUID
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

    // ====================== 006 — role inheritance ========================

    /** Creates a role via the web API and returns it. */
    private RoleResponse newRole(UUID admin, String name) {
        return rest.exchange("/api/web/permission/roles", HttpMethod.POST, auth(admin, role(name)),
                RoleResponse.class).getBody();
    }

    @Test
    void adminAddsListsAndRemovesInheritanceAndEffectiveShowsProvenance() {
        UUID admin = staff("ADMIN");
        RoleResponse base = newRole(admin, "InhBase");
        rest.exchange("/api/web/permission/roles/" + base.id() + "/permissions", HttpMethod.POST,
                auth(admin, new RolePermissionWriteRequest("base.home")), RoleResponse.class);
        RoleResponse premium = newRole(admin, "InhPremium");

        // add edge Premium -> Base
        RoleResponse afterAdd = rest.exchange("/api/web/permission/roles/" + premium.id() + "/inheritance",
                HttpMethod.POST, auth(admin, new InheritanceWriteRequest(base.id())), RoleResponse.class).getBody();
        assertThat(afterAdd.inheritedRoleIds()).contains(base.id());

        // list
        Long[] parents = rest.exchange("/api/web/permission/roles/" + premium.id() + "/inheritance",
                HttpMethod.GET, auth(admin, null), Long[].class).getBody();
        assertThat(parents).containsExactly(base.id());

        // a player holding only Premium effectively has base.home, with provenance pointing at Base
        UUID player = UUID.randomUUID();
        rest.exchange("/api/web/permission/players/" + player + "/roles", HttpMethod.POST,
                auth(admin, new GrantRoleWriteRequest(premium.id(), null, null)), PlayerPermissionsResponse.class);
        PlayerPermissionsResponse view = rest.exchange("/api/web/permission/players/" + player + "/effective",
                HttpMethod.GET, auth(admin, null), PlayerPermissionsResponse.class).getBody();
        assertThat(view.effectivePermissions()).contains("base.home");
        assertThat(view.sources()).anySatisfy(s -> {
            assertThat(s.permission()).isEqualTo("base.home");
            assertThat(s.own()).isFalse();
            assertThat(s.inheritedFromRoleIds()).contains(base.id());
        });

        // remove edge → permission gone
        rest.exchange("/api/web/permission/roles/" + premium.id() + "/inheritance/" + base.id(),
                HttpMethod.DELETE, auth(admin, null), RoleResponse.class);
        PlayerPermissionsResponse after = rest.exchange("/api/web/permission/players/" + player + "/effective",
                HttpMethod.GET, auth(admin, null), PlayerPermissionsResponse.class).getBody();
        assertThat(after.effectivePermissions()).doesNotContain("base.home");
    }

    @Test
    void inheritanceForbiddenWithoutInheritGate() {
        UUID admin = staff("ADMIN");
        UUID mod = staff("MODERATOR"); // lacks permission.role.edit.inherit
        RoleResponse base = newRole(admin, "GateBase");
        RoleResponse child = newRole(admin, "GateChild");
        ResponseEntity<String> r = rest.exchange("/api/web/permission/roles/" + child.id() + "/inheritance",
                HttpMethod.POST, auth(mod, new InheritanceWriteRequest(base.id())), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void cyclicInheritanceReturns409() {
        UUID admin = staff("ADMIN");
        RoleResponse a = newRole(admin, "CycWebA");
        RoleResponse b = newRole(admin, "CycWebB");
        rest.exchange("/api/web/permission/roles/" + a.id() + "/inheritance", HttpMethod.POST,
                auth(admin, new InheritanceWriteRequest(b.id())), RoleResponse.class); // A -> B
        ResponseEntity<String> r = rest.exchange("/api/web/permission/roles/" + b.id() + "/inheritance",
                HttpMethod.POST, auth(admin, new InheritanceWriteRequest(a.id())), String.class); // B -> A
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("role_inheritance_cycle");
    }

    @Test
    void defaultRoleCannotInheritReturns409() {
        UUID admin = staff("ADMIN");
        RoleResponse base = newRole(admin, "DefInhBase");
        ResponseEntity<String> r = rest.exchange("/api/web/permission/roles/" + defaultRoleId() + "/inheritance",
                HttpMethod.POST, auth(admin, new InheritanceWriteRequest(base.id())), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("default_role_protected");
    }

    @Test
    void deletingAnInheritedRoleReturns409() {
        UUID admin = staff("ADMIN");
        RoleResponse base = newRole(admin, "DelInhBase");
        RoleResponse child = newRole(admin, "DelInhChild");
        rest.exchange("/api/web/permission/roles/" + child.id() + "/inheritance", HttpMethod.POST,
                auth(admin, new InheritanceWriteRequest(base.id())), RoleResponse.class);
        ResponseEntity<String> r = rest.exchange("/api/web/permission/roles/" + base.id(),
                HttpMethod.DELETE, auth(admin, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("role_inherited");
    }

    // ====================== 008 — authority ceiling =======================

    /** A short unique role name (the role-name column is length-limited, so no full UUIDs). */
    private String uniq(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    private RoleWriteRequest roleW(String name, int weight) {
        return new RoleWriteRequest(name, name, null, null, null, null, null, null, weight, false, true);
    }

    /** Creates a weighted role with the given permissions and grants it to a fresh actor (returns its uuid). */
    private UUID managerActor(int weight, String... perms) {
        UUID admin = staff("ADMIN");
        RoleResponse mgr = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, roleW(uniq("Mgr"), weight)), RoleResponse.class).getBody();
        for (String p : perms) {
            rest.exchange("/api/web/permission/roles/" + mgr.id() + "/permissions", HttpMethod.POST,
                    auth(admin, new RolePermissionWriteRequest(p)), RoleResponse.class);
        }
        UUID actor = UUID.randomUUID();
        rest.exchange("/api/web/permission/players/" + actor + "/roles", HttpMethod.POST,
                auth(admin, new GrantRoleWriteRequest(mgr.id(), null, null)), PlayerPermissionsResponse.class);
        return actor;
    }

    private long roleId(String name) {
        return dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq(name)).fetchOne(ROLE.ID);
    }

    @Test
    void authorityBlocksDelegatingStarOrUnheldPermission() {
        UUID admin = staff("ADMIN");
        UUID mgr = managerActor(50, "permission.role.edit", "permission.grant.permission", "home.set");
        RoleResponse low = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(admin, roleW(uniq("Low"), 10)), RoleResponse.class).getBody();

        assertThat(rest.exchange("/api/web/permission/roles/" + low.id() + "/permissions", HttpMethod.POST,
                auth(mgr, new RolePermissionWriteRequest("*")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN); // wildcard without *
        assertThat(rest.exchange("/api/web/permission/players/" + UUID.randomUUID() + "/permissions", HttpMethod.POST,
                auth(mgr, new GrantPermissionWriteRequest("*", null, null)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange("/api/web/permission/roles/" + low.id() + "/permissions", HttpMethod.POST,
                auth(mgr, new RolePermissionWriteRequest("home.set")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK); // holds it → allowed
    }

    @Test
    void authorityBlocksManagingOrGrantingHigherRoleAndTarget() {
        UUID mgr = managerActor(50, "permission.role.create", "permission.role.edit", "permission.grant.role");
        long adminRoleId = roleId("ADMIN");
        UUID adminPlayer = staff("ADMIN"); // authority 100 ≥ 50

        assertThat(rest.exchange("/api/web/permission/roles/" + adminRoleId + "/permissions", HttpMethod.POST,
                auth(mgr, new RolePermissionWriteRequest("home.set")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN); // editing a higher role
        assertThat(rest.exchange("/api/web/permission/players/" + UUID.randomUUID() + "/roles", HttpMethod.POST,
                auth(mgr, new GrantRoleWriteRequest(adminRoleId, null, null)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN); // granting a higher role
        assertThat(rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(mgr, roleW(uniq("Peer"), 50)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN); // creating at own weight (strict <)
        // manage a higher-authority target (an ADMIN) → 403
        RoleResponse low = rest.exchange("/api/web/permission/roles", HttpMethod.POST,
                auth(staff("ADMIN"), roleW(uniq("L"), 5)), RoleResponse.class).getBody();
        assertThat(rest.exchange("/api/web/permission/players/" + adminPlayer + "/roles", HttpMethod.POST,
                auth(mgr, new GrantRoleWriteRequest(low.id(), null, null)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void readsAreBoundedByAuthorityButSearchAndMeAreNot() {
        UUID mgr = managerActor(50, "permission.read");
        long adminRoleId = roleId("ADMIN");
        UUID adminPlayer = staff("ADMIN");

        RoleResponse[] visible = rest.exchange("/api/web/permission/roles", HttpMethod.GET,
                auth(mgr, null), RoleResponse[].class).getBody();
        assertThat(visible).extracting(RoleResponse::name).doesNotContain("ADMIN"); // FR-009

        assertThat(rest.exchange("/api/web/permission/roles/" + adminRoleId, HttpMethod.GET,
                auth(mgr, null), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // FR-009a
        assertThat(rest.exchange("/api/web/permission/players/" + adminPlayer + "/effective", HttpMethod.GET,
                auth(mgr, null), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // FR-010

        UUID named = playerNamed("HighGuyAdmin");
        rest.exchange("/api/web/permission/players/" + named + "/roles", HttpMethod.POST,
                auth(staff("ADMIN"), new GrantRoleWriteRequest(adminRoleId, null, null)), PlayerPermissionsResponse.class);
        PlayerSummary[] hits = rest.exchange("/api/web/players/search?name=HighGuy", HttpMethod.GET,
                auth(mgr, null), PlayerSummary[].class).getBody();
        assertThat(hits).extracting(PlayerSummary::name).contains("HighGuyAdmin"); // search unfiltered (FR-010a)

        MeResponse me = rest.exchange("/api/web/me", HttpMethod.GET, auth(mgr, null), MeResponse.class).getBody();
        assertThat(me.uuid()).isEqualTo(mgr); // /me unbounded (FR-011)
    }

    @Test
    void ownPermissionsTabIsAlwaysVisible() {
        UUID mgr = managerActor(50, "permission.read"); // non-top, may read
        ResponseEntity<PlayerPermissionsResponse> r = rest.exchange(
                "/api/web/permission/players/" + mgr + "/effective", HttpMethod.GET,
                auth(mgr, null), PlayerPermissionsResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK); // must see one's OWN permissions
        assertThat(r.getBody().player()).isEqualTo(mgr);
    }

    @Test
    void lastTopTierHolderCannotSelfDemote() {
        long adminRoleId = roleId("ADMIN");
        dsl.update(PLAYER_ROLE_GRANT).set(PLAYER_ROLE_GRANT.ACTIVE, false)
                .where(PLAYER_ROLE_GRANT.ROLE_ID.eq(adminRoleId)).execute(); // clear the top tier
        UUID owner = staff("ADMIN"); // now the sole active ADMIN

        ResponseEntity<String> r = rest.exchange(
                "/api/web/permission/players/" + owner + "/roles/" + adminRoleId, HttpMethod.DELETE,
                auth(owner, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("last_top_tier");
    }

    @Test
    void inheritanceChangePublishesLiveEventToAffectedHolders() throws Exception {
        UUID admin = staff("ADMIN");
        RoleResponse base = newRole(admin, "LiveBase");
        RoleResponse premium = newRole(admin, "LivePremium");
        UUID player = UUID.randomUUID();
        rest.exchange("/api/web/permission/players/" + player + "/roles", HttpMethod.POST,
                auth(admin, new GrantRoleWriteRequest(premium.id(), null, null)), PlayerPermissionsResponse.class);

        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (AutoCloseable sub = redis.subscribe(
                com.mcplatform.protocol.permission.PermissionChannels.CHANGED, events::offer)) {
            Thread.sleep(200);
            rest.exchange("/api/web/permission/roles/" + premium.id() + "/inheritance", HttpMethod.POST,
                    auth(admin, new InheritanceWriteRequest(base.id())), RoleResponse.class);

            String wire = events.poll(3, TimeUnit.SECONDS);
            assertThat(wire).as("inheritance change published to the Premium holder").isNotNull();
            assertThat(wire).contains("ROLE_CONFIG_CHANGED");
            assertThat(wire).contains(player.toString());
        }
    }
}
