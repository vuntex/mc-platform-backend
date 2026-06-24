package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.PLAYER;
import static com.mcplatform.persistence.jooq.Tables.WEB_ACCOUNT;
import static com.mcplatform.persistence.jooq.Tables.WEB_AUTH_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.protocol.webauth.RedeemRequest;
import com.mcplatform.protocol.webauth.TokenResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
 * End-to-end vertical slice for the web-auth bridge: REST → service → jOOQ (web_account + web_link_token
 * + web_auth_audit). Covers link → redeem (account created, BCrypt hash, no plaintext), the
 * second-link/reset-without-account 409s, the reset flow, the uniform 410 (unknown/expired/used token),
 * the 422 password policy, the 429 cooldown, and the DTO JSON contract (no hash/email on the wire).
 * No Redis path for this feature, but a Redis container is provided so the app context wires cleanly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WebAuthVerticalSliceTest {

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
    DSLContext dsl;

    private UUID newPlayer() {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, uuid)
                .set(PLAYER.NAME, "Steve")
                .set(PLAYER.NAME_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
        return uuid;
    }

    private TokenResponse requestLink(UUID uuid) {
        ResponseEntity<TokenResponse> r = rest.postForEntity(
                "/api/players/{uuid}/web-auth/link-token", null, TokenResponse.class, uuid);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private HttpStatusCode redeem(String token, String password) {
        return rest.postForEntity(
                "/api/web-auth/redeem", new RedeemRequest(token, password), String.class).getStatusCode();
    }

    @Test
    void linkThenRedeemCreatesAccountWithBcryptHash() {
        UUID uuid = newPlayer();
        TokenResponse token = requestLink(uuid);
        assertThat(token.token()).isNotBlank();
        assertThat(token.purpose()).isEqualTo("LINK");

        assertThat(redeem(token.token(), "password123")).isEqualTo(HttpStatus.NO_CONTENT);

        String hash = dsl.select(WEB_ACCOUNT.PASSWORD_HASH).from(WEB_ACCOUNT)
                .where(WEB_ACCOUNT.PLAYER_UUID.eq(uuid)).fetchOneInto(String.class);
        assertThat(hash).startsWith("$2");            // BCrypt
        assertThat(hash).isNotEqualTo("password123"); // never plaintext
        assertThat(dsl.fetchCount(WEB_AUTH_AUDIT,
                WEB_AUTH_AUDIT.PLAYER_UUID.eq(uuid).and(WEB_AUTH_AUDIT.EVENT_TYPE.eq("ACCOUNT_CREATED"))))
                .isEqualTo(1);
    }

    @Test
    void secondLinkWhenAccountExistsIsConflict() {
        UUID uuid = newPlayer();
        redeem(requestLink(uuid).token(), "password123");

        ResponseEntity<String> second = rest.postForEntity(
                "/api/players/{uuid}/web-auth/link-token", null, String.class, uuid);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("web_account_exists");
    }

    @Test
    void resetFlowOverwritesPassword() {
        UUID uuid = newPlayer();
        redeem(requestLink(uuid).token(), "password123");

        ResponseEntity<TokenResponse> resetToken = rest.postForEntity(
                "/api/players/{uuid}/web-auth/reset-token", null, TokenResponse.class, uuid);
        assertThat(resetToken.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resetToken.getBody().purpose()).isEqualTo("RESET");

        assertThat(redeem(resetToken.getBody().token(), "newpassword456")).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dsl.fetchCount(WEB_AUTH_AUDIT,
                WEB_AUTH_AUDIT.PLAYER_UUID.eq(uuid).and(WEB_AUTH_AUDIT.EVENT_TYPE.eq("PASSWORD_RESET"))))
                .isEqualTo(1);
    }

    @Test
    void resetWithoutAccountIsConflict() {
        UUID uuid = newPlayer();
        ResponseEntity<String> r = rest.postForEntity(
                "/api/players/{uuid}/web-auth/reset-token", null, String.class, uuid);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("web_account_missing");
    }

    @Test
    void unknownTokenIsGone() {
        assertThat(redeem("does-not-exist", "password123")).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void replayOfConsumedTokenIsGone() {
        UUID uuid = newPlayer();
        String token = requestLink(uuid).token();
        assertThat(redeem(token, "password123")).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(redeem(token, "password123")).isEqualTo(HttpStatus.GONE); // single-use
    }

    @Test
    void weakPasswordIsUnprocessable() {
        UUID uuid = newPlayer();
        String token = requestLink(uuid).token();
        assertThat(redeem(token, "short")).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void secondTokenWithinCooldownIsTooManyRequests() {
        UUID uuid = newPlayer();
        requestLink(uuid); // first token, not redeemed → account still absent
        ResponseEntity<String> second = rest.postForEntity(
                "/api/players/{uuid}/web-auth/link-token", null, String.class, uuid);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getBody()).contains("web_auth_cooldown");
    }

    @Test
    void tokenResponseWireCarriesNoHashOrEmail() {
        UUID uuid = newPlayer();
        ResponseEntity<String> r = rest.postForEntity(
                "/api/players/{uuid}/web-auth/link-token", null, String.class, uuid);
        assertThat(r.getBody()).contains("\"token\"", "\"purpose\"", "\"expiresAtEpochMilli\"");
        assertThat(r.getBody()).doesNotContain("hash", "password", "email", "username");
    }
}
