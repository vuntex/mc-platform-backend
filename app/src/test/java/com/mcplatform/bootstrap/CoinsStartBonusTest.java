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
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the V3 migration: COINS default_balance is 100, so a brand-new player joins with a visible
 * start bonus, while pre-existing players are untouched (the migration changes only the currency
 * default for future joins, never existing player_balance rows). Deliberately does NOT override the
 * currency default — it relies on the value Flyway applied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class CoinsStartBonusTest {

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
    void migrationSetsCoinsDefaultTo100() {
        assertThat(dsl.select(CURRENCY.DEFAULT_BALANCE).from(CURRENCY)
                .where(CURRENCY.CODE.eq("COINS")).fetchOne(CURRENCY.DEFAULT_BALANCE))
                .isEqualTo(100L);
    }

    @Test
    void newPlayerJoinsWith100CoinsAndASystemInitialCreditedEvent() {
        UUID player = UUID.randomUUID();

        SessionJoinResponse join = join(player, "Steve");

        assertThat(join.created()).isTrue();
        assertThat(coins(join).balance()).isEqualTo(100);

        // The bonus entered through the event store: exactly one SYSTEM:initial CREDITED event.
        assertThat(dsl.fetchCount(ECONOMY_EVENT, ECONOMY_EVENT.PLAYER_UUID.eq(player)
                .and(ECONOMY_EVENT.CURRENCY_CODE.eq("COINS"))
                .and(ECONOMY_EVENT.EVENT_TYPE.eq("CREDITED"))
                .and(ECONOMY_EVENT.SOURCE.eq("SYSTEM:initial"))
                .and(ECONOMY_EVENT.AMOUNT.eq(100L)))).isEqualTo(1);
        assertThat(balanceOf(player, "COINS")).isEqualTo(100);
    }

    @Test
    void existingPlayerIsNotRetroactivelyGrantedTheBonus() {
        // Simulate a player who joined BEFORE the bonus existed: present, balance 0, no events.
        UUID player = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, player).set(PLAYER.NAME, "Veteran")
                .set(PLAYER.NAME_UPDATED_AT, now).set(PLAYER.LAST_SEEN, now)
                .execute();
        dsl.insertInto(PLAYER_BALANCE)
                .set(PLAYER_BALANCE.PLAYER_UUID, player).set(PLAYER_BALANCE.CURRENCY_CODE, "COINS")
                .set(PLAYER_BALANCE.BALANCE, 0L).set(PLAYER_BALANCE.VERSION, 0L)
                .execute();

        SessionJoinResponse join = join(player, "Veteran");

        // Existing player: not re-created, no init credit, balance stays 0.
        assertThat(join.created()).isFalse();
        assertThat(coins(join).balance()).isEqualTo(0);
        assertThat(balanceOf(player, "COINS")).isEqualTo(0);
        assertThat(dsl.fetchCount(ECONOMY_EVENT, ECONOMY_EVENT.PLAYER_UUID.eq(player))).isZero();
    }

    // --- helpers -----------------------------------------------------------

    private SessionJoinResponse join(UUID player, String name) {
        return rest.exchange("/api/players/" + player + "/session/join", HttpMethod.POST,
                new HttpEntity<>(new PlayerRequest(name)), JOIN_RESPONSE).getBody();
    }

    private static BalanceResponse coins(SessionJoinResponse join) {
        return join.balances().stream().filter(b -> b.currency().equals("COINS")).findFirst().orElseThrow();
    }

    private long balanceOf(UUID player, String currency) {
        return dsl.select(PLAYER_BALANCE.BALANCE).from(PLAYER_BALANCE)
                .where(PLAYER_BALANCE.PLAYER_UUID.eq(player).and(PLAYER_BALANCE.CURRENCY_CODE.eq(currency)))
                .fetchOne(PLAYER_BALANCE.BALANCE);
    }
}
