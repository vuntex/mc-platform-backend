package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.CURRENCY;
import static com.mcplatform.persistence.jooq.Tables.ECONOMY_EVENT;
import static com.mcplatform.persistence.jooq.Tables.PLAYER;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_BALANCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.session.PlayerRequest;
import com.mcplatform.protocol.session.SessionJoinResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
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
 * End-to-end test for the session-join use case: player ensured + default balances initialised via
 * the event store, idempotent on repeat joins, name/last_seen refreshed. Asserts the persisted event
 * store and projection directly (jOOQ) on top of the REST surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PlayerSessionJoinTest {

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

    private static final ParameterizedTypeReference<SessionJoinResponse> JOIN_RESPONSE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DSLContext dsl;

    @Test
    void firstJoinInitialisesConfiguredDefaultThroughTheEventStore() {
        // The starting amount is config, not code: set it on the currency row (as the web UI would).
        dsl.update(CURRENCY).set(CURRENCY.DEFAULT_BALANCE, 500L).where(CURRENCY.CODE.eq("COINS")).execute();
        UUID player = UUID.randomUUID();

        SessionJoinResponse first = join(player, "Steve");

        assertThat(first.created()).isTrue();
        assertThat(coins(first).balance()).isEqualTo(500);
        assertThat(coins(first).version()).isPositive();

        // player + projection exist, seeded via a single SYSTEM:initial CREDITED event.
        assertThat(dsl.fetchExists(PLAYER, PLAYER.UUID.eq(player))).isTrue();
        assertThat(balanceOf(player, "COINS")).isEqualTo(500);
        assertThat(initialCreditCount(player, "COINS")).isEqualTo(1);
    }

    @Test
    void secondJoinIsIdempotentButRefreshesNameAndLastSeen() {
        dsl.update(CURRENCY).set(CURRENCY.DEFAULT_BALANCE, 500L).where(CURRENCY.CODE.eq("COINS")).execute();
        UUID player = UUID.randomUUID();

        join(player, "Steve");
        OffsetDateTime nameUpdatedFirst = nameUpdatedAt(player);
        OffsetDateTime lastSeenFirst = lastSeen(player);

        SessionJoinResponse second = join(player, "Steve_Renamed");

        // Idempotent: not created again, no second initial credit, balance unchanged.
        assertThat(second.created()).isFalse();
        assertThat(coins(second).balance()).isEqualTo(500);
        assertThat(initialCreditCount(player, "COINS")).isEqualTo(1);
        assertThat(balanceOf(player, "COINS")).isEqualTo(500);

        // But identity cache is refreshed.
        assertThat(dsl.select(PLAYER.NAME).from(PLAYER).where(PLAYER.UUID.eq(player)).fetchOne(PLAYER.NAME))
                .isEqualTo("Steve_Renamed");
        assertThat(nameUpdatedAt(player)).isAfterOrEqualTo(nameUpdatedFirst);
        assertThat(lastSeen(player)).isAfterOrEqualTo(lastSeenFirst);
    }

    @Test
    void zeroDefaultMaterialisesAZeroRowWithoutAnEvent() {
        dsl.insertInto(CURRENCY)
                .set(CURRENCY.CODE, "GEMS")
                .set(CURRENCY.DISPLAY_NAME, "Gems")
                .set(CURRENCY.DECIMAL_PLACES, (short) 0)
                .set(CURRENCY.DEFAULT_BALANCE, 0L)
                .onConflict(CURRENCY.CODE).doUpdate().set(CURRENCY.DEFAULT_BALANCE, 0L)
                .execute();
        UUID player = UUID.randomUUID();

        join(player, "Alex");

        // Consistent 0 projection, but no pointless CREDITED 0 in the event store.
        assertThat(balanceOf(player, "GEMS")).isEqualTo(0);
        assertThat(dsl.fetchCount(ECONOMY_EVENT,
                ECONOMY_EVENT.PLAYER_UUID.eq(player).and(ECONOMY_EVENT.CURRENCY_CODE.eq("GEMS")))).isZero();
    }

    // --- helpers -----------------------------------------------------------

    private SessionJoinResponse join(UUID player, String name) {
        ResponseEntity<SessionJoinResponse> response = rest.exchange(
                "/api/players/" + player + "/session/join", HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(new PlayerRequest(name)), JOIN_RESPONSE);
        return response.getBody();
    }

    private static BalanceResponse coins(SessionJoinResponse join) {
        return join.balances().stream().filter(b -> b.currency().equals("COINS")).findFirst().orElseThrow();
    }

    private long balanceOf(UUID player, String currency) {
        return dsl.select(PLAYER_BALANCE.BALANCE).from(PLAYER_BALANCE)
                .where(PLAYER_BALANCE.PLAYER_UUID.eq(player).and(PLAYER_BALANCE.CURRENCY_CODE.eq(currency)))
                .fetchOne(PLAYER_BALANCE.BALANCE);
    }

    private int initialCreditCount(UUID player, String currency) {
        return dsl.fetchCount(ECONOMY_EVENT, ECONOMY_EVENT.PLAYER_UUID.eq(player)
                .and(ECONOMY_EVENT.CURRENCY_CODE.eq(currency))
                .and(ECONOMY_EVENT.EVENT_TYPE.eq("CREDITED"))
                .and(ECONOMY_EVENT.SOURCE.eq("SYSTEM:initial")));
    }

    private OffsetDateTime nameUpdatedAt(UUID player) {
        return dsl.select(PLAYER.NAME_UPDATED_AT).from(PLAYER).where(PLAYER.UUID.eq(player))
                .fetchOne(PLAYER.NAME_UPDATED_AT);
    }

    private OffsetDateTime lastSeen(UUID player) {
        return dsl.select(PLAYER.LAST_SEEN).from(PLAYER).where(PLAYER.UUID.eq(player))
                .fetchOne(PLAYER.LAST_SEEN);
    }
}
