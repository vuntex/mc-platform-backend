package com.mcplatform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
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
 * Brings up Postgres + Redis via Testcontainers, lets Spring Boot run Flyway, and verifies:
 *   1. the Spring context loads,
 *   2. every table from PROGRESS.md section 6 exists,
 *   3. the V2 seed currency (COINS) is present,
 *   4. GET /actuator/health answers 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SmokeTest {

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
    DataSource dataSource;

    @Autowired
    TestRestTemplate rest;

    @Test
    void schemaIsMigratedAndHealthIsUp() throws Exception {
        Set<String> expected = Set.of(
                "player", "currency", "economy_event",
                "player_balance", "server_config", "config_audit");

        Set<String> actual = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.getMetaData()
                        .getTables(null, "public", "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                actual.add(rs.getString("TABLE_NAME"));
            }
        }
        assertThat(actual).as("all schema tables exist").containsAll(expected);

        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM currency WHERE code = 'COINS'")) {
            rs.next();
            assertThat(rs.getInt(1)).as("seed currency COINS present").isEqualTo(1);
        }

        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
