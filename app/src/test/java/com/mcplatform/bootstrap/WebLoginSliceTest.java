package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.PLAYER;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.bootstrap.adapter.JwtTokenService;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.webauth.LoginRequest;
import com.mcplatform.protocol.webauth.RedeemRequest;
import com.mcplatform.protocol.webauth.TokenPairResponse;
import com.mcplatform.protocol.webauth.TokenResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end vertical slice for the JWT-login session: REST → service → jOOQ, through the real Spring
 * Security chain. Covers login → access+refresh, the protected {@code /api/web/**} surface with
 * valid/missing/expired access tokens, refresh rotation + replay (whole family killed), logout, the
 * uniform 401 (no name-vs-password leak / no account → same), the CSRF guard, and the JSON contract
 * (no refresh token in the body). A test-scoped probe controller is the only protected endpoint
 * (no production /api/web endpoint exists yet — that is slice 6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(WebLoginSliceTest.ProbeController.class) // register the test-only probe exactly once
class WebLoginSliceTest {

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

    /**
     * Test-only protected endpoint under /api/web/** — proves the filter + chain and returns the identity.
     * Picked up by the application's component scan (com.mcplatform); registered exactly once.
     */
    @RestController
    static class ProbeController {
        @GetMapping("/api/web/__probe")
        String probe(@AuthenticationPrincipal PlayerId caller) {
            return caller.value().toString();
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DSLContext dsl;

    @Value("${mcplatform.webauth.jwt.secret}")
    String jwtSecret;

    // --- helpers ----------------------------------------------------------

    private UUID newPlayer(String name) {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, uuid)
                .set(PLAYER.NAME, name)
                .set(PLAYER.NAME_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
        return uuid;
    }

    /** Create a player + a web account with the given password (via the bridge link→redeem flow). */
    private UUID newAccount(String name, String password) {
        UUID uuid = newPlayer(name);
        TokenResponse link = rest.postForEntity(
                "/api/players/{uuid}/web-auth/link-token", null, TokenResponse.class, uuid).getBody();
        rest.postForEntity("/api/web-auth/redeem", new RedeemRequest(link.token(), password), String.class);
        return uuid;
    }

    private ResponseEntity<TokenPairResponse> login(String name, String password) {
        return rest.postForEntity("/api/web-auth/login", new LoginRequest(name, password), TokenPairResponse.class);
    }

    private String refreshCookie(ResponseEntity<?> response) {
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        for (String c : setCookies) {
            if (c.startsWith("mcweb_refresh=")) {
                return c.substring("mcweb_refresh=".length(), c.indexOf(';'));
            }
        }
        throw new AssertionError("no mcweb_refresh cookie in " + setCookies);
    }

    private ResponseEntity<String> getProbe(String accessToken) {
        HttpHeaders h = new HttpHeaders();
        if (accessToken != null) {
            h.setBearerAuth(accessToken);
        }
        return rest.exchange("/api/web/__probe", HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private ResponseEntity<String> refresh(String cookieValue, boolean withCsrfHeader) {
        HttpHeaders h = new HttpHeaders();
        if (cookieValue != null) {
            h.add(HttpHeaders.COOKIE, "mcweb_refresh=" + cookieValue);
        }
        if (withCsrfHeader) {
            h.add("X-Refresh", "1");
        }
        return rest.exchange("/api/web-auth/session/refresh", HttpMethod.POST, new HttpEntity<>(h), String.class);
    }

    private ResponseEntity<String> logout(String cookieValue) {
        HttpHeaders h = new HttpHeaders();
        if (cookieValue != null) {
            h.add(HttpHeaders.COOKIE, "mcweb_refresh=" + cookieValue);
        }
        h.add("X-Refresh", "1");
        return rest.exchange("/api/web-auth/session/logout", HttpMethod.POST, new HttpEntity<>(h), String.class);
    }

    // --- US1: login + protected access ------------------------------------

    @Test
    void loginIssuesTokenPairAndRefreshCookie() {
        newAccount("Vuntex", "password123");
        ResponseEntity<TokenPairResponse> r = login("Vuntex", "password123");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().accessToken()).isNotBlank();
        assertThat(r.getBody().accessExpiresAtEpochMilli()).isPositive();
        assertThat(refreshCookie(r)).isNotBlank();
    }

    @Test
    void protectedEndpointRequiresValidAccessToken() {
        UUID uuid = newAccount("Probe", "password123");
        String access = login("Probe", "password123").getBody().accessToken();

        // valid → 200 and the identity propagates
        ResponseEntity<String> ok = getProbe(access);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo(uuid.toString());

        // missing → 401
        assertThat(getProbe(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // expired (signed with the SAME secret the app uses, but issued in the past) → 401
        String expired = new JwtTokenService(jwtSecret)
                .issue(PlayerId.of(uuid), Duration.ofMinutes(15), Instant.now().minus(Duration.ofHours(1)));
        assertThat(getProbe(expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginFailsUniformlyForWrongPasswordUnknownNameAndNoAccount() {
        newAccount("Uni", "password123");
        ResponseEntity<String> wrongPw = rest.postForEntity(
                "/api/web-auth/login", new LoginRequest("Uni", "wrong-password"), String.class);
        ResponseEntity<String> unknown = rest.postForEntity(
                "/api/web-auth/login", new LoginRequest("Ghost", "password123"), String.class);
        newPlayer("Linked"); // player exists but no web account
        ResponseEntity<String> noAccount = rest.postForEntity(
                "/api/web-auth/login", new LoginRequest("Linked", "password123"), String.class);

        assertThat(wrongPw.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noAccount.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // identical error code — no enumeration of which part was wrong
        assertThat(wrongPw.getBody()).contains("web_auth_invalid_credentials");
        assertThat(unknown.getBody()).contains("web_auth_invalid_credentials");
        assertThat(noAccount.getBody()).contains("web_auth_invalid_credentials");
    }

    @Test
    void loginBodyNeverCarriesTheRefreshToken() {
        newAccount("NoLeak", "password123");
        ResponseEntity<String> raw = rest.postForEntity(
                "/api/web-auth/login", new LoginRequest("NoLeak", "password123"), String.class);
        assertThat(raw.getBody()).contains("accessToken");
        assertThat(raw.getBody()).doesNotContain("refreshToken");
        // the refresh token only appears in the Set-Cookie header
        assertThat(refreshCookie(raw)).isNotBlank();
    }

    // --- US2: refresh + rotation + replay ---------------------------------

    @Test
    void refreshRotatesAndReplayKillsTheWholeFamily() {
        newAccount("Rotor", "password123");
        String r1 = refreshCookie(login("Rotor", "password123"));

        ResponseEntity<String> refreshed = refresh(r1, true);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String r2 = refreshCookie(refreshed);
        assertThat(r2).isNotBlank().isNotEqualTo(r1);

        // replay the already-rotated r1 → 401 session revoked
        ResponseEntity<String> replay = refresh(r1, true);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody()).contains("web_auth_session_revoked");

        // the family is dead: even the previously-valid r2 no longer works
        assertThat(refresh(r2, true).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshWithoutCsrfHeaderIsForbidden() {
        newAccount("Csrf", "password123");
        String r1 = refreshCookie(login("Csrf", "password123"));
        assertThat(refresh(r1, false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refreshWithUnknownTokenIsUnauthorized() {
        assertThat(refresh("totally-unknown", true).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- US3: logout ------------------------------------------------------

    @Test
    void logoutInvalidatesTheRefreshTokenAndIsIdempotent() {
        newAccount("ByeBye", "password123");
        String r1 = refreshCookie(login("ByeBye", "password123"));

        assertThat(logout(r1).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // the token can no longer refresh
        assertThat(refresh(r1, true).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // logging out an unknown/already-gone token still succeeds (idempotent)
        assertThat(logout("already-gone").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- SC-007: existing internal endpoints stay reachable ---------------

    @Test
    void existingPluginEndpointsRemainPermitAll() {
        // a bridge endpoint (not under /api/web/**) must work without any access token
        UUID uuid = newPlayer("Internal");
        ResponseEntity<TokenResponse> r = rest.postForEntity(
                "/api/players/{uuid}/web-auth/link-token", null, TokenResponse.class, uuid);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
