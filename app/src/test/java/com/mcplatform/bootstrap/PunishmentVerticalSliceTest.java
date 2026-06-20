package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.TEAM_ROLE_MEMBER;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.punishment.IssueFromTemplateRequest;
import com.mcplatform.protocol.punishment.IssueRequest;
import com.mcplatform.protocol.punishment.PunishmentChangedEvent;
import com.mcplatform.protocol.punishment.PunishmentChangedEventCodec;
import com.mcplatform.protocol.punishment.PunishmentChannels;
import com.mcplatform.protocol.punishment.PunishmentResponse;
import com.mcplatform.protocol.punishment.RevokeRequest;
import com.mcplatform.protocol.punishment.TemplateResponse;
import com.mcplatform.protocol.session.PlayerRequest;
import java.time.Duration;
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
 * End-to-end vertical slice for punishments: REST → service (permission + coexistence) → jOOQ
 * (event + projection) → Redis Pub/Sub. Covers issue → active → revoke, template permission gating
 * (200/403) and the live-update event on {@code mc:punishment:changed}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PunishmentVerticalSliceTest {

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
        dsl.insertInto(TEAM_ROLE_MEMBER).set(TEAM_ROLE_MEMBER.UUID, uuid).set(TEAM_ROLE_MEMBER.ROLE, role).execute();
        return uuid;
    }

    private UUID player(String name) {
        UUID uuid = UUID.randomUUID();
        rest.put("/api/players/" + uuid, new PlayerRequest(name));
        return uuid;
    }

    @Test
    void issueAppearsInActiveThenRevokeRemovesItAndPublishes() throws Exception {
        UUID admin = staff("ADMIN");
        UUID target = player("Griefer");

        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (AutoCloseable subscription = redis.subscribe(PunishmentChannels.CHANGED, events::offer)) {
            Thread.sleep(200); // let the subscription register

            PunishmentResponse issued = rest.postForObject("/api/players/" + target + "/punishments",
                    new IssueRequest("TEMPBAN", "Cheating", Duration.ofDays(7).toMillis(), admin, null, "WEB"),
                    PunishmentResponse.class);
            assertThat(issued.active()).isTrue();
            assertThat(issued.type()).isEqualTo("TEMPBAN");
            assertThat(issued.expiresAtEpochMilli()).isPositive();

            String wire = events.poll(3, TimeUnit.SECONDS);
            assertThat(wire).as("a punishment-changed event was published").isNotNull();
            PunishmentChangedEvent published =
                    PlatformProtocol.create().decode(wire, PunishmentChangedEventCodec.INSTANCE);
            assertThat(published.action()).isEqualTo("ISSUED");
            assertThat(published.type()).isEqualTo("TEMPBAN");

            PunishmentResponse[] active = rest.getForObject(
                    "/api/players/" + target + "/punishments/active", PunishmentResponse[].class);
            assertThat(active).hasSize(1);
            assertThat(active[0].id()).isEqualTo(issued.id());

            rest.postForObject("/api/punishments/" + issued.id() + "/revoke",
                    new RevokeRequest(admin, "appeal granted", null, "WEB"), PunishmentResponse.class);

            PunishmentResponse[] afterRevoke = rest.getForObject(
                    "/api/players/" + target + "/punishments/active", PunishmentResponse[].class);
            assertThat(afterRevoke).isEmpty();
        }
    }

    @Test
    void secondActiveBanReturns409() {
        UUID admin = staff("ADMIN");
        UUID target = player("Repeat");

        rest.postForObject("/api/players/" + target + "/punishments",
                new IssueRequest("TEMPBAN", "first", Duration.ofDays(1).toMillis(), admin, null, "WEB"),
                PunishmentResponse.class);

        ResponseEntity<String> second = rest.postForEntity("/api/players/" + target + "/punishments",
                new IssueRequest("PERMABAN", "second", null, admin, null, "WEB"), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fromTemplateIsGatedByPermission() {
        UUID moderator = staff("MODERATOR");
        UUID target = player("Spammer");

        // MODERATOR holds punishment.spam -> the 'spam' template applies (200).
        ResponseEntity<PunishmentResponse> allowed = rest.postForEntity(
                "/api/players/" + target + "/punishments/from-template",
                new IssueFromTemplateRequest("spam", null, moderator, null, "WEB"), PunishmentResponse.class);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getBody().type()).isEqualTo("CHATBAN");

        // MODERATOR lacks punishment.cheating -> the 'cheating' template is forbidden (403).
        ResponseEntity<String> denied = rest.postForEntity(
                "/api/players/" + target + "/punishments/from-template",
                new IssueFromTemplateRequest("cheating", null, moderator, null, "WEB"), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listTemplatesReportsCanApplyPerMember() {
        UUID moderator = staff("MODERATOR");

        TemplateResponse[] templates = rest.getForObject(
                "/api/punishments/templates?staff=" + moderator, TemplateResponse[].class);

        assertThat(templates).isNotEmpty();
        assertThat(templates).filteredOn(t -> t.key().equals("spam")).singleElement()
                .satisfies(t -> assertThat(t.canApply()).isTrue());
        assertThat(templates).filteredOn(t -> t.key().equals("cheating")).singleElement()
                .satisfies(t -> assertThat(t.canApply()).isFalse());
    }
}
