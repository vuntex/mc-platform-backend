package com.mcplatform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.BalanceResponse;
import com.mcplatform.protocol.economy.TransferRequest;
import com.mcplatform.protocol.economy.TransferResponse;
import com.mcplatform.protocol.session.PlayerRequest;
import com.mcplatform.cache.RedisCacheAdapter;
import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.economy.BalanceChangedEvent;
import com.mcplatform.protocol.economy.BalanceChangedEventCodec;
import com.mcplatform.protocol.economy.EconomyChannels;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
 * End-to-end vertical slice through the whole stack: REST → service → jOOQ (Postgres event +
 * projection) → Redis cache + Pub/Sub. The "it lives" moment for the economy write path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class EconomyVerticalSliceTest {

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

    @Test
    void creditDebitFlowsThroughPostgresCacheAndPubSub() throws Exception {
        UUID player = UUID.randomUUID();
        rest.put("/api/players/" + player, new PlayerRequest("Steve"));

        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (AutoCloseable subscription = redis.subscribe(EconomyChannels.BALANCE, events::offer)) {
            Thread.sleep(200); // let the subscription register

            BalanceResponse afterCredit = rest.postForObject(
                    "/api/players/" + player + "/balances/COINS/credit",
                    new AmountRequest(100, null, "TEST"), BalanceResponse.class);
            assertThat(afterCredit.balance()).isEqualTo(100);
            assertThat(afterCredit.version()).isPositive();

            String wire = events.poll(3, TimeUnit.SECONDS);
            assertThat(wire).as("a balance-changed event was published").isNotNull();
            BalanceChangedEvent published =
                    PlatformProtocol.create().decode(wire, BalanceChangedEventCodec.INSTANCE);
            assertThat(published.balance()).isEqualTo(100);
            assertThat(published.eventType()).isEqualTo("CREDITED");
        }

        BalanceResponse afterDebit = rest.postForObject(
                "/api/players/" + player + "/balances/COINS/debit",
                new AmountRequest(30, null, "TEST"), BalanceResponse.class);
        assertThat(afterDebit.balance()).isEqualTo(70);

        BalanceResponse current = rest.getForObject(
                "/api/players/" + player + "/balances/COINS", BalanceResponse.class);
        assertThat(current.balance()).isEqualTo(70);
    }

    @Test
    void transferMovesMoneyBetweenTwoPlayers() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        rest.put("/api/players/" + alice, new PlayerRequest("Alice"));
        rest.put("/api/players/" + bob, new PlayerRequest("Bob"));

        rest.postForObject("/api/players/" + alice + "/balances/COINS/credit",
                new AmountRequest(100, null, "TEST"), BalanceResponse.class);

        TransferResponse transfer = rest.postForObject(
                "/api/players/" + alice + "/balances/COINS/transfer",
                new TransferRequest(bob, 30, null, "WEB:transfer"), TransferResponse.class);

        assertThat(transfer.from().balance()).isEqualTo(70);
        assertThat(transfer.to().balance()).isEqualTo(30);

        assertThat(rest.getForObject("/api/players/" + alice + "/balances/COINS", BalanceResponse.class)
                .balance()).isEqualTo(70);
        assertThat(rest.getForObject("/api/players/" + bob + "/balances/COINS", BalanceResponse.class)
                .balance()).isEqualTo(30);
    }

    @Test
    void debitBeyondBalanceReturns422() {
        UUID player = UUID.randomUUID();
        rest.put("/api/players/" + player, new PlayerRequest("Alex"));

        ResponseEntity<String> response = rest.postForEntity(
                "/api/players/" + player + "/balances/COINS/debit",
                new AmountRequest(10, null, "TEST"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
