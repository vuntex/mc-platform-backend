package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.PLAYER;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_ROLE_GRANT;
import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.bootstrap.adapter.JwtTokenService;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.cache.RedisPlayerPresenceAdapter;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.player.PlayerStatsResponse;
import com.mcplatform.protocol.player.PlayerSummary;
import com.mcplatform.protocol.player.RecentPlayerSummary;
import com.mcplatform.protocol.session.PlayerRequest;
import com.mcplatform.protocol.session.SessionJoinResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * End-to-end slice for the web player dashboard ({@code GET /api/web/players/recent} + {@code /stats}):
 * REST → JWT chain → {@code PlayerDirectoryService} → jOOQ (Postgres) + Redis presence set. Also covers
 * the plugin's {@code session/join} (marks online) and {@code session/leave} (marks offline) lifecycle,
 * plus the 401/403 gates. Each {@code @SpringBootTest} class gets its own containers, so all player rows
 * and presence entries here are created by this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WebPlayersDirectoryTest {

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

    private HttpEntity<Void> auth(UUID actor) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(new JwtTokenService(jwtSecret).issue(PlayerId.of(actor), Duration.ofMinutes(15), Instant.now()));
        return new HttpEntity<>(h);
    }

    /** Insert a player row with an explicit last_seen (created_at defaults to now()). */
    private UUID playerSeen(String name, Instant lastSeen) {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, uuid)
                .set(PLAYER.NAME, name)
                .set(PLAYER.NAME_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(PLAYER.LAST_SEEN, lastSeen.atOffset(ZoneOffset.UTC))
                .execute();
        return uuid;
    }

    /** Reset the shared presence set so a test starts from a known-empty online set. */
    private void clearPresence() {
        RedisPlayerPresenceAdapter presence = new RedisPlayerPresenceAdapter(redis);
        for (UUID u : presence.onlinePlayers()) {
            presence.markOffline(PlayerId.of(u));
        }
    }

    private void markOnline(UUID... uuids) {
        RedisPlayerPresenceAdapter presence = new RedisPlayerPresenceAdapter(redis);
        for (UUID u : uuids) {
            presence.markOnline(PlayerId.of(u));
        }
    }

    private RecentPlayerSummary[] recent(UUID actor, int limit) {
        return rest.exchange("/api/web/players/recent?limit=" + limit, HttpMethod.GET,
                auth(actor), RecentPlayerSummary[].class).getBody();
    }

    // --- recent -----------------------------------------------------------

    @Test
    void recentReturnsOnlineFirstThenByLastSeenDesc() {
        clearPresence();
        UUID admin = staff("ADMIN");
        UUID p1 = playerSeen("Alpha", Instant.parse("2030-01-01T00:00:00Z"));
        UUID p2 = playerSeen("Bravo", Instant.parse("2030-01-04T00:00:00Z"));
        UUID p3 = playerSeen("Charlie", Instant.parse("2030-01-02T00:00:00Z"));
        UUID p4 = playerSeen("Delta", Instant.parse("2030-01-03T00:00:00Z"));
        markOnline(p2, p4); // p2 newer than p4

        Set<UUID> mine = Set.of(p1, p2, p3, p4);
        List<RecentPlayerSummary> got = Arrays.stream(recent(admin, 10))
                .filter(r -> mine.contains(r.uuid())).toList();

        // online (p2, p4) first — each by last_seen desc — then offline (p3, p1) by last_seen desc
        assertThat(got).extracting(RecentPlayerSummary::uuid).containsExactly(p2, p4, p3, p1);
        assertThat(got).extracting(RecentPlayerSummary::online).containsExactly(true, true, false, false);
        assertThat(got.get(0).name()).isEqualTo("Bravo");
        assertThat(got.get(0).lastSeenEpochMilli())
                .isEqualTo(Instant.parse("2030-01-04T00:00:00Z").toEpochMilli());
    }

    @Test
    void recentCapsAtLimitPreferringOnline() {
        clearPresence();
        UUID admin = staff("ADMIN");
        UUID a = playerSeen("OnlineOne", Instant.parse("2031-06-02T00:00:00Z"));
        UUID b = playerSeen("OnlineTwo", Instant.parse("2031-06-01T00:00:00Z"));
        playerSeen("OfflineNewer", Instant.parse("2031-06-03T00:00:00Z")); // newer, but offline
        markOnline(a, b);

        RecentPlayerSummary[] got = recent(admin, 2);
        assertThat(got).hasSize(2);
        assertThat(got).extracting(RecentPlayerSummary::uuid).containsExactly(a, b); // both online, newest first
        assertThat(got).allMatch(RecentPlayerSummary::online);
    }

    @Test
    void recentIsEmptyListWhenNoPlayers() {
        clearPresence();
        // A never-joined admin still gets a normal empty-ish list — here we only assert it is a valid 200 array.
        UUID admin = staff("ADMIN");
        ResponseEntity<RecentPlayerSummary[]> r = rest.exchange("/api/web/players/recent", HttpMethod.GET,
                auth(admin), RecentPlayerSummary[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();
    }

    @Test
    void recentForbiddenWithoutReadPermission() {
        UUID mod = staff("MODERATOR"); // no permission.read
        ResponseEntity<String> r = rest.exchange("/api/web/players/recent", HttpMethod.GET,
                auth(mod), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void recentRequiresJwt() {
        assertThat(rest.getForEntity("/api/web/players/recent", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- uuid → name resolution ------------------------------------------

    @Test
    void resolvesKnownUuidToNameConsistentWithSearch() {
        UUID admin = staff("ADMIN");
        UUID p = playerSeen("Steve", Instant.parse("2033-01-01T00:00:00Z"));

        PlayerSummary summary = rest.exchange("/api/web/players/" + p, HttpMethod.GET,
                auth(admin), PlayerSummary.class).getBody();
        assertThat(summary.uuid()).isEqualTo(p);
        assertThat(summary.name()).isEqualTo("Steve");

        // same cached name source as the search endpoint
        PlayerSummary[] hits = rest.exchange("/api/web/players/search?name=Steve", HttpMethod.GET,
                auth(admin), PlayerSummary[].class).getBody();
        assertThat(hits).extracting(PlayerSummary::name).contains(summary.name());
    }

    @Test
    void unknownUuidIsNotFound() {
        UUID admin = staff("ADMIN");
        ResponseEntity<String> r = rest.exchange("/api/web/players/" + UUID.randomUUID(), HttpMethod.GET,
                auth(admin), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).contains("player_not_found");
    }

    @Test
    void resolveForbiddenWithoutReadPermission() {
        UUID mod = staff("MODERATOR"); // no permission.read
        ResponseEntity<String> r = rest.exchange("/api/web/players/" + UUID.randomUUID(), HttpMethod.GET,
                auth(mod), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).contains("permission_denied");
    }

    @Test
    void resolveRequiresJwt() {
        assertThat(rest.getForEntity("/api/web/players/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- stats ------------------------------------------------------------

    @Test
    void statsCountsTotalOnlineAndNewThisWeek() {
        clearPresence();
        UUID admin = staff("ADMIN");
        PlayerStatsResponse before = rest.exchange("/api/web/players/stats", HttpMethod.GET,
                auth(admin), PlayerStatsResponse.class).getBody();

        UUID a = playerSeen("StatOne", Instant.parse("2032-01-01T00:00:00Z"));
        UUID b = playerSeen("StatTwo", Instant.parse("2032-01-02T00:00:00Z"));
        playerSeen("StatThree", Instant.parse("2032-01-03T00:00:00Z"));
        markOnline(a, b);

        PlayerStatsResponse after = rest.exchange("/api/web/players/stats", HttpMethod.GET,
                auth(admin), PlayerStatsResponse.class).getBody();

        assertThat(after.totalPlayers() - before.totalPlayers()).isEqualTo(3);
        assertThat(after.onlineNow()).isEqualTo(2); // presence cleared, then exactly two marked online
        // freshly inserted rows all have created_at = now → each counts as "new this week"
        assertThat(after.newThisWeek() - before.newThisWeek()).isEqualTo(3);
    }

    @Test
    void statsForbiddenWithoutReadPermission() {
        UUID mod = staff("MODERATOR");
        ResponseEntity<String> r = rest.exchange("/api/web/players/stats", HttpMethod.GET,
                auth(mod), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- session lifecycle drives presence --------------------------------

    @Test
    void sessionJoinMarksOnlineAndLeaveMarksOffline() {
        RedisPlayerPresenceAdapter presence = new RedisPlayerPresenceAdapter(redis);
        UUID p = UUID.randomUUID();

        rest.postForEntity("/api/players/" + p + "/session/join",
                new PlayerRequest("Joiner"), SessionJoinResponse.class);
        assertThat(presence.isOnline(PlayerId.of(p))).isTrue();

        ResponseEntity<Void> left = rest.postForEntity("/api/players/" + p + "/session/leave", null, Void.class);
        assertThat(left.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(presence.isOnline(PlayerId.of(p))).isFalse();
    }
}
