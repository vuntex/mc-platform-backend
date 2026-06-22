package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.PLAYER_ROLE_GRANT;
import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.report.ChangeStatusRequest;
import com.mcplatform.protocol.report.ChatMessage;
import com.mcplatform.protocol.report.CreateReportRequest;
import com.mcplatform.protocol.report.ReportChangedEvent;
import com.mcplatform.protocol.report.ReportChangedEventCodec;
import com.mcplatform.protocol.report.ReportChannels;
import com.mcplatform.protocol.report.ReportResponse;
import com.mcplatform.protocol.session.PlayerRequest;
import java.util.List;
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
 * End-to-end vertical slice for reports: REST → service (dedupe + cooldown + permissions + lifecycle) →
 * jOOQ (report + chat snapshot + status history) → Redis Pub/Sub. Covers create (+chat round-trip),
 * self-report (422), dedupe, cooldown (429), the open list (+403), the status lifecycle (200/409/404/403)
 * and the live {@code mc:report:changed} event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ReportVerticalSliceTest {

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

    private UUID player(String name) {
        UUID uuid = UUID.randomUUID();
        rest.put("/api/players/" + uuid, new PlayerRequest(name));
        return uuid;
    }

    private UUID staff(String role) {
        UUID uuid = player("Staff");
        long roleId = dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq(role)).fetchOne(ROLE.ID);
        dsl.insertInto(PLAYER_ROLE_GRANT)
                .set(PLAYER_ROLE_GRANT.UUID, uuid)
                .set(PLAYER_ROLE_GRANT.ROLE_ID, roleId)
                .set(PLAYER_ROLE_GRANT.ISSUED_BY, uuid)
                .set(PLAYER_ROLE_GRANT.ACTIVE, true)
                .execute();
        return uuid;
    }

    // --- US1: create ------------------------------------------------------

    @Test
    void createReturnsReportAndRoundTripsChatContext() {
        UUID reporter = player("Reporter");
        UUID target = player("Cheater");

        CreateReportRequest req = new CreateReportRequest(reporter, target, "CHEATING", "flying in the mine",
                List.of(new ChatMessage(target, "lol ez", 1_750_000_000_000L)));
        ReportResponse created = rest.postForObject("/api/reports", req, ReportResponse.class);

        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.category()).isEqualTo("CHEATING");
        assertThat(created.detail()).isEqualTo("flying in the mine");
        assertThat(created.chatContext()).singleElement()
                .satisfies(m -> assertThat(m.text()).isEqualTo("lol ez"));
        assertThat(created.lastHandledBy()).isNull();
    }

    @Test
    void selfReportReturns422() {
        UUID self = player("Lonely");
        ResponseEntity<String> res = rest.postForEntity("/api/reports",
                new CreateReportRequest(self, self, "SONSTIGES", "me", null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void duplicateOpenReportReturnsTheSameReport() {
        UUID reporter = player("Dupe");
        UUID target = player("Same");

        ReportResponse first = rest.postForObject("/api/reports",
                new CreateReportRequest(reporter, target, "BELEIDIGUNG", "first", null), ReportResponse.class);
        ReportResponse second = rest.postForObject("/api/reports",
                new CreateReportRequest(reporter, target, "BELEIDIGUNG", "second", null), ReportResponse.class);

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void rapidSecondReportHitsCooldown429() {
        UUID reporter = player("Spammer");
        UUID targetA = player("VictimA");
        UUID targetB = player("VictimB");

        assertThat(rest.postForEntity("/api/reports",
                new CreateReportRequest(reporter, targetA, "CHEATING", "a", null), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/reports",
                new CreateReportRequest(reporter, targetB, "CHEATING", "b", null), String.class).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // --- US2: open list ---------------------------------------------------

    @Test
    void openListIsVisibleToTeamAndForbiddenToOthers() {
        UUID reporter = player("Lister");
        UUID target = player("Listed");
        ReportResponse created = rest.postForObject("/api/reports",
                new CreateReportRequest(reporter, target, "TEAMING_BUG_ABUSE", "teaming", null), ReportResponse.class);

        UUID admin = staff("ADMIN");
        ReportResponse[] open = rest.getForObject("/api/reports/open?staff=" + admin, ReportResponse[].class);
        assertThat(open).extracting(ReportResponse::id).contains(created.id());

        ResponseEntity<String> denied = rest.getForEntity("/api/reports/open?staff=" + player("Nobody"), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- US3: status lifecycle -------------------------------------------

    @Test
    void statusLifecycleHappyPathAndClosingRemovesFromOpenList() {
        UUID admin = staff("ADMIN");
        UUID reporter = player("Flow");
        UUID target = player("Flowed");
        ReportResponse created = rest.postForObject("/api/reports",
                new CreateReportRequest(reporter, target, "CHEATING", "x", null), ReportResponse.class);

        ReportResponse inProgress = rest.postForObject("/api/reports/" + created.id() + "/status",
                new ChangeStatusRequest("IN_PROGRESS", admin), ReportResponse.class);
        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.lastHandledBy()).isEqualTo(admin);

        ReportResponse resolved = rest.postForObject("/api/reports/" + created.id() + "/status",
                new ChangeStatusRequest("RESOLVED", admin), ReportResponse.class);
        assertThat(resolved.status()).isEqualTo("RESOLVED");

        ReportResponse[] open = rest.getForObject("/api/reports/open?staff=" + admin, ReportResponse[].class);
        assertThat(open).extracting(ReportResponse::id).doesNotContain(created.id());
    }

    @Test
    void illegalTransitionReturns409() {
        UUID admin = staff("ADMIN");
        UUID reporter = player("Jump");
        UUID target = player("Jumped");
        ReportResponse created = rest.postForObject("/api/reports",
                new CreateReportRequest(reporter, target, "CHEATING", "x", null), ReportResponse.class);

        ResponseEntity<String> res = rest.postForEntity("/api/reports/" + created.id() + "/status",
                new ChangeStatusRequest("RESOLVED", admin), String.class);  // OPEN -> RESOLVED not allowed
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknownReportReturns404() {
        UUID admin = staff("ADMIN");
        ResponseEntity<String> res = rest.postForEntity("/api/reports/" + UUID.randomUUID() + "/status",
                new ChangeStatusRequest("IN_PROGRESS", admin), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statusChangeWithoutPermissionReturns403() {
        UUID reporter = player("Perm");
        UUID target = player("Permed");
        ReportResponse created = rest.postForObject("/api/reports",
                new CreateReportRequest(reporter, target, "CHEATING", "x", null), ReportResponse.class);

        ResponseEntity<String> res = rest.postForEntity("/api/reports/" + created.id() + "/status",
                new ChangeStatusRequest("IN_PROGRESS", player("NotStaff")), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- US4: live event --------------------------------------------------

    @Test
    void createAndStatusChangePublishReportChangedEvents() throws Exception {
        UUID admin = staff("ADMIN");
        UUID reporter = player("Live");
        UUID target = player("Watched");

        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (AutoCloseable subscription = redis.subscribe(ReportChannels.CHANGED, events::offer)) {
            Thread.sleep(200); // let the subscription register

            ReportResponse created = rest.postForObject("/api/reports",
                    new CreateReportRequest(reporter, target, "CHEATING", "x", null), ReportResponse.class);

            ReportChangedEvent createdEvent = PlatformProtocol.create()
                    .decode(events.poll(3, TimeUnit.SECONDS), ReportChangedEventCodec.INSTANCE);
            assertThat(createdEvent.changeType()).isEqualTo("CREATED");
            assertThat(createdEvent.status()).isEqualTo("OPEN");
            assertThat(createdEvent.reportId()).isEqualTo(created.id());

            rest.postForObject("/api/reports/" + created.id() + "/status",
                    new ChangeStatusRequest("IN_PROGRESS", admin), ReportResponse.class);

            ReportChangedEvent statusEvent = PlatformProtocol.create()
                    .decode(events.poll(3, TimeUnit.SECONDS), ReportChangedEventCodec.INSTANCE);
            assertThat(statusEvent.changeType()).isEqualTo("STATUS_CHANGED");
            assertThat(statusEvent.status()).isEqualTo("IN_PROGRESS");
        }
    }
}
