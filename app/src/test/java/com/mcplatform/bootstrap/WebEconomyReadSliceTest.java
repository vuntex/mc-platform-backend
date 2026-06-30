package com.mcplatform.bootstrap;

import static com.mcplatform.persistence.jooq.Tables.ECONOMY_EVENT;
import static com.mcplatform.persistence.jooq.Tables.PLAYER;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_BALANCE;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_ROLE_GRANT;
import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.bootstrap.adapter.JwtTokenService;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.economy.AmountRequest;
import com.mcplatform.protocol.economy.EconomyHistoryResponse;
import com.mcplatform.protocol.economy.PlayerBalanceEntry;
import com.mcplatform.protocol.economy.PlayerBalancesResponse;
import com.mcplatform.protocol.economy.TransactionDetailResponse;
import com.mcplatform.protocol.economy.TransactionLegDto;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * End-to-end vertical slice for the JWT-gated web economy READ surface ({@code /api/web/economy/**}, spec
 * 007): REST → Spring Security (JWT) → read use cases → jOOQ. US1 (player balances aggregate): all
 * currencies in one call with display metadata, empty list for an unknown player (no 404),
 * {@code permission.economy.read} gate (403), JWT required (401). Further stories are added as their
 * phases land.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WebEconomyReadSliceTest {

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

    @Value("${mcplatform.webauth.jwt.secret}")
    String jwtSecret;

    @LocalServerPort
    int port;

    // --- helpers ----------------------------------------------------------

    private String token(UUID actor) {
        return new JwtTokenService(jwtSecret).issue(PlayerId.of(actor), Duration.ofMinutes(15), Instant.now());
    }

    /** A staff member holding {@code role}; ADMIN carries the "*" wildcard, MODERATOR does not. */
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

    private <T> HttpEntity<T> auth(UUID actor, T body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(new JwtTokenService(jwtSecret).issue(PlayerId.of(actor), Duration.ofMinutes(15), Instant.now()));
        return new HttpEntity<>(body, h);
    }

    /** Inserts a player with COINS (seeded) + GEMS balances; returns the player uuid. */
    private UUID playerWithBalances() {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, uuid)
                .set(PLAYER.NAME, "Holder")
                .set(PLAYER.NAME_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
        dsl.execute("INSERT INTO currency (code, display_name, symbol, decimal_places, default_balance) "
                + "VALUES ('GEMS', 'Gems', NULL, 0, 0) ON CONFLICT (code) DO NOTHING");
        insertBalance(uuid, "COINS", 100);
        insertBalance(uuid, "GEMS", 5);
        return uuid;
    }

    private void insertBalance(UUID player, String currency, long balance) {
        dsl.insertInto(PLAYER_BALANCE)
                .set(PLAYER_BALANCE.PLAYER_UUID, player)
                .set(PLAYER_BALANCE.CURRENCY_CODE, currency)
                .set(PLAYER_BALANCE.BALANCE, balance)
                .set(PLAYER_BALANCE.VERSION, 1L)
                .execute();
    }

    private String balancesPath(UUID player) {
        return "/api/web/economy/players/" + player + "/balances";
    }

    private UUID playerNamed(String name) {
        UUID uuid = UUID.randomUUID();
        dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, uuid)
                .set(PLAYER.NAME, name)
                .set(PLAYER.NAME_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute();
        return uuid;
    }

    private void insertEvent(UUID player, String currency, String type, long amount, long balanceAfter, String source) {
        insertEventTx(player, currency, type, amount, balanceAfter, source, UUID.randomUUID(), null);
    }

    private void insertEventTx(UUID player, String currency, String type, long amount, long balanceAfter,
            String source, UUID transactionId, UUID correlationId) {
        org.jooq.JSONB metadata = correlationId == null
                ? null
                : org.jooq.JSONB.valueOf("{\"correlation_id\":\"" + correlationId + "\"}");
        dsl.insertInto(ECONOMY_EVENT)
                .set(ECONOMY_EVENT.PLAYER_UUID, player)
                .set(ECONOMY_EVENT.CURRENCY_CODE, currency)
                .set(ECONOMY_EVENT.EVENT_TYPE, type)
                .set(ECONOMY_EVENT.AMOUNT, amount)
                .set(ECONOMY_EVENT.BALANCE_AFTER, balanceAfter)
                .set(ECONOMY_EVENT.TRANSACTION_ID, transactionId)
                .set(ECONOMY_EVENT.SOURCE, source)
                .set(ECONOMY_EVENT.METADATA, metadata)
                .execute();
    }

    // --- US1: player balances aggregate -----------------------------------

    @Test
    void balancesReturnsAllCurrenciesWithDisplayMetadata() {
        UUID admin = staff("ADMIN");
        UUID player = playerWithBalances();

        PlayerBalancesResponse body = rest.exchange(balancesPath(player), HttpMethod.GET,
                auth(admin, null), PlayerBalancesResponse.class).getBody();

        assertThat(body.player()).isEqualTo(player);
        assertThat(body.balances()).extracting(PlayerBalanceEntry::currencyCode)
                .containsExactlyInAnyOrder("COINS", "GEMS");
        PlayerBalanceEntry gems = body.balances().stream()
                .filter(b -> b.currencyCode().equals("GEMS")).findFirst().orElseThrow();
        assertThat(gems.displayName()).isEqualTo("Gems");
        assertThat(gems.balance()).isEqualTo(5);
    }

    @Test
    void balancesEmptyForUnknownPlayerNoNotFound() {
        UUID admin = staff("ADMIN");
        ResponseEntity<PlayerBalancesResponse> r = rest.exchange(balancesPath(UUID.randomUUID()),
                HttpMethod.GET, auth(admin, null), PlayerBalancesResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().balances()).isEmpty();
    }

    @Test
    void balancesForbiddenWithoutEconomyReadPermission() {
        UUID mod = staff("MODERATOR"); // no permission.economy.read, no "*"
        ResponseEntity<String> r = rest.exchange(balancesPath(UUID.randomUUID()), HttpMethod.GET,
                auth(mod, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void balancesRequiresJwt() {
        assertThat(rest.getForEntity(balancesPath(UUID.randomUUID()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- US2: server-wide transaction history -----------------------------

    @Test
    void serverHistoryReturnsEntriesWithPlayerNameAndSourceFilter() {
        UUID admin = staff("ADMIN");
        UUID p = playerNamed("HistGuy");
        insertEvent(p, "COINS", "CREDITED", 100, 100, "WEBSHOP-e2e");

        EconomyHistoryResponse body = rest.exchange("/api/web/economy/history?source=WEBSHOP-e2e",
                HttpMethod.GET, auth(admin, null), EconomyHistoryResponse.class).getBody();

        assertThat(body.player()).isNull(); // server-wide → no single player
        assertThat(body.entries()).hasSize(1);
        assertThat(body.entries().get(0).playerUuid()).isEqualTo(p);
        assertThat(body.entries().get(0).playerName()).isEqualTo("HistGuy");
        assertThat(body.entries().get(0).source()).isEqualTo("WEBSHOP-e2e");
    }

    @Test
    void serverHistoryRejectsInvalidTypeAndNonPositiveLimit() {
        UUID admin = staff("ADMIN");
        assertThat(rest.exchange("/api/web/economy/history?type=BOGUS", HttpMethod.GET,
                auth(admin, null), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/api/web/economy/history?limit=0", HttpMethod.GET,
                auth(admin, null), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void serverHistoryForbiddenWithoutPermission() {
        UUID mod = staff("MODERATOR");
        assertThat(rest.exchange("/api/web/economy/history", HttpMethod.GET,
                auth(mod, null), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void serverHistoryRequiresJwt() {
        assertThat(rest.getForEntity("/api/web/economy/history", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- US3: transaction detail ------------------------------------------

    @Test
    void transactionDetailSingleHasOneLeg() {
        UUID admin = staff("ADMIN");
        UUID p = playerNamed("TxSingle");
        UUID tx = UUID.randomUUID();
        insertEventTx(p, "COINS", "CREDITED", 42, 42, "WEB", tx, null);

        TransactionDetailResponse d = rest.exchange("/api/web/economy/transactions/" + tx, HttpMethod.GET,
                auth(admin, null), TransactionDetailResponse.class).getBody();

        assertThat(d.kind()).isEqualTo("SINGLE");
        assertThat(d.legs()).hasSize(1);
        assertThat(d.legs().get(0).playerName()).isEqualTo("TxSingle");
        assertThat(d.amount()).isEqualTo(42);
    }

    @Test
    void transactionDetailTransferHasTwoLegsWithBothNames() {
        UUID admin = staff("ADMIN");
        UUID sender = playerNamed("TxSender");
        UUID receiver = playerNamed("TxReceiver");
        UUID outTx = UUID.randomUUID();
        UUID inTx = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        insertEventTx(sender, "COINS", "TRANSFER_OUT", 30, 70, "WEB:transfer", outTx, corr);
        insertEventTx(receiver, "COINS", "TRANSFER_IN", 30, 30, "WEB:transfer", inTx, corr);

        TransactionDetailResponse d = rest.exchange("/api/web/economy/transactions/" + outTx, HttpMethod.GET,
                auth(admin, null), TransactionDetailResponse.class).getBody();

        assertThat(d.kind()).isEqualTo("TRANSFER");
        assertThat(d.correlationId()).isEqualTo(corr);
        assertThat(d.legs()).extracting(TransactionLegDto::playerName)
                .containsExactlyInAnyOrder("TxSender", "TxReceiver");
    }

    @Test
    void transactionDetailUnknownReturns404() {
        UUID admin = staff("ADMIN");
        ResponseEntity<String> r = rest.exchange("/api/web/economy/transactions/" + UUID.randomUUID(),
                HttpMethod.GET, auth(admin, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).contains("economy_transaction_not_found");
    }

    @Test
    void transactionDetailForbiddenWithoutPermission() {
        UUID mod = staff("MODERATOR");
        assertThat(rest.exchange("/api/web/economy/transactions/" + UUID.randomUUID(), HttpMethod.GET,
                auth(mod, null), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- US4: live SSE stream ---------------------------------------------

    /** Opens an SSE connection and pushes each {@code data:} line into the returned queue. */
    private CompletableFuture<HttpResponse<Void>> openStream(UUID actor, String query, LinkedBlockingQueue<String> dataLines) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/web/economy/stream" + query))
                .header("Authorization", "Bearer " + token(actor))
                .header("Accept", "text/event-stream")
                .GET().build();
        return HttpClient.newHttpClient().sendAsync(req, HttpResponse.BodyHandlers.fromLineSubscriber(
                new Flow.Subscriber<String>() {
                    @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    @Override public void onNext(String line) { if (line.startsWith("data:")) dataLines.offer(line); }
                    @Override public void onError(Throwable t) { }
                    @Override public void onComplete() { }
                }));
    }

    private void credit(UUID player, long amount) {
        rest.postForEntity("/api/players/" + player + "/balances/COINS/credit",
                new AmountRequest(amount, null, "WEB"), String.class);
    }

    @Test
    void streamReceivesBalanceChangeLive() throws Exception {
        UUID admin = staff("ADMIN");
        UUID player = playerNamed("StreamGuy");
        LinkedBlockingQueue<String> dataLines = new LinkedBlockingQueue<>();
        CompletableFuture<HttpResponse<Void>> stream = openStream(admin, "", dataLines);
        try {
            Thread.sleep(600); // let the SSE registration + Redis subscription settle
            credit(player, 10);
            String line = dataLines.poll(5, TimeUnit.SECONDS);
            assertThat(line).as("SSE client received a live balance change").isNotNull();
            assertThat(line).contains(player.toString());
        } finally {
            stream.cancel(true);
        }
    }

    @Test
    void streamPlayerFilterDeliversOnlyTheMatchingPlayer() throws Exception {
        UUID admin = staff("ADMIN");
        UUID target = playerNamed("FilterTarget");
        UUID other = playerNamed("FilterOther");
        LinkedBlockingQueue<String> dataLines = new LinkedBlockingQueue<>();
        CompletableFuture<HttpResponse<Void>> stream = openStream(admin, "?player=" + target, dataLines);
        try {
            Thread.sleep(600);
            credit(other, 5);   // must be filtered out server-side
            credit(target, 7);  // must arrive
            String line = dataLines.poll(5, TimeUnit.SECONDS);
            assertThat(line).as("only the matching player's event is delivered").isNotNull();
            assertThat(line).contains(target.toString());
            assertThat(line).doesNotContain(other.toString());
        } finally {
            stream.cancel(true);
        }
    }

    @Test
    void streamForbiddenWithoutPermission() {
        UUID mod = staff("MODERATOR");
        assertThat(rest.exchange("/api/web/economy/stream", HttpMethod.GET, auth(mod, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void streamRequiresJwt() {
        assertThat(rest.getForEntity("/api/web/economy/stream", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
