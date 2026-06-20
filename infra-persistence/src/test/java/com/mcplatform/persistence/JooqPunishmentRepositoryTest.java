package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.PUNISHMENT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.punishment.port.PunishmentNotFoundException;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.punishment.PendingPunishmentEvent;
import com.mcplatform.domain.punishment.Punishment;
import com.mcplatform.domain.punishment.PunishmentConflictException;
import com.mcplatform.domain.punishment.PunishmentEventType;
import com.mcplatform.domain.punishment.PunishmentId;
import com.mcplatform.domain.punishment.PunishmentTxId;
import com.mcplatform.domain.punishment.PunishmentType;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration test for the jOOQ punishment adapter against a real, Flyway-migrated Postgres. */
@Testcontainers
class JooqPunishmentRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqPunishmentRepository punishments;
    static JooqPlayerRepository players;

    private final PlayerId staff = PlayerId.of(UUID.randomUUID());

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        punishments = new JooqPunishmentRepository(dsl);
        players = new JooqPlayerRepository(dsl);
    }

    private PlayerId newPlayer() {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, "Steve", Instant.now());
        return p;
    }

    private PendingPunishmentEvent issueEvent(PlayerId player, PunishmentType type, Instant now, Instant expiresAt) {
        return new PendingPunishmentEvent(PunishmentId.random(), player, PunishmentEventType.ISSUED, type,
                "reason", staff, now, expiresAt, PunishmentTxId.random(), "TEST");
    }

    @Test
    void issueProjectsAnActivePunishment() {
        PlayerId p = newPlayer();
        Instant now = Instant.now();
        PendingPunishmentEvent event = issueEvent(p, PunishmentType.TEMPBAN, now, now.plusSeconds(3600));

        Punishment issued = punishments.issue(event, now);
        assertThat(issued.version()).isPositive();
        assertThat(issued.isActive(now)).isTrue();

        assertThat(punishments.activeForPlayer(p, now)).extracting(Punishment::id).containsExactly(issued.id());
        assertThat(punishments.find(issued.id())).isPresent();
    }

    @Test
    void revokeMakesPunishmentInactive() {
        PlayerId p = newPlayer();
        Instant now = Instant.now();
        Punishment issued = punishments.issue(issueEvent(p, PunishmentType.PERMABAN, now, null), now);

        PendingPunishmentEvent revoke = new PendingPunishmentEvent(issued.id(), p, PunishmentEventType.REVOKED,
                null, "appeal", staff, now.plusSeconds(1), null, PunishmentTxId.random(), "TEST");
        Punishment revoked = punishments.revoke(revoke);

        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.version()).isGreaterThan(issued.version());
        assertThat(punishments.activeForPlayer(p, now.plusSeconds(2))).isEmpty();
    }

    @Test
    void issueIsIdempotentOnTransactionId() {
        PlayerId p = newPlayer();
        Instant now = Instant.now();
        PendingPunishmentEvent event = issueEvent(p, PunishmentType.WARN, now, null);

        Punishment first = punishments.issue(event, now);
        Punishment replay = punishments.issue(event, now);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.version()).isEqualTo(first.version());
        long rows = dsl.fetchCount(PUNISHMENT_EVENT, PUNISHMENT_EVENT.TRANSACTION_ID.eq(event.transactionId().value()));
        assertThat(rows).as("only one event row for the transaction id").isEqualTo(1);
    }

    @Test
    void secondActiveBanInSameCategoryConflicts() {
        PlayerId p = newPlayer();
        Instant now = Instant.now();
        punishments.issue(issueEvent(p, PunishmentType.TEMPBAN, now, now.plusSeconds(3600)), now);

        PendingPunishmentEvent secondBan = issueEvent(p, PunishmentType.PERMABAN, now, null);
        assertThatThrownBy(() -> punishments.issue(secondBan, now))
                .isInstanceOf(PunishmentConflictException.class);
    }

    @Test
    void chatbanCoexistsWithBan() {
        PlayerId p = newPlayer();
        Instant now = Instant.now();
        punishments.issue(issueEvent(p, PunishmentType.TEMPBAN, now, now.plusSeconds(3600)), now);
        punishments.issue(issueEvent(p, PunishmentType.CHATBAN, now, now.plusSeconds(3600)), now);

        assertThat(punishments.activeForPlayer(p, now)).hasSize(2);
    }

    @Test
    void expiredBanDoesNotBlockANewBan() {
        PlayerId p = newPlayer();
        Instant now = Instant.now();
        // An already-expired (but unrevoked) tempban occupies no active slot.
        punishments.issue(issueEvent(p, PunishmentType.TEMPBAN, now.minusSeconds(7200), now.minusSeconds(1)), now);

        Punishment fresh = punishments.issue(issueEvent(p, PunishmentType.TEMPBAN, now, now.plusSeconds(3600)), now);
        assertThat(punishments.activeForPlayer(p, now)).extracting(Punishment::id).containsExactly(fresh.id());
    }

    @Test
    void revokingUnknownPunishmentIsNotFound() {
        PlayerId p = newPlayer();
        Instant now = Instant.now();
        PendingPunishmentEvent revoke = new PendingPunishmentEvent(PunishmentId.random(), p,
                PunishmentEventType.REVOKED, null, "x", staff, now, null, PunishmentTxId.random(), "TEST");
        assertThatThrownBy(() -> punishments.revoke(revoke)).isInstanceOf(PunishmentNotFoundException.class);
    }

    @Test
    void revokingAnAlreadyRevokedPunishmentConflicts() {
        PlayerId p = newPlayer();
        Instant now = Instant.now();
        Punishment issued = punishments.issue(issueEvent(p, PunishmentType.PERMABAN, now, null), now);
        punishments.revoke(new PendingPunishmentEvent(issued.id(), p, PunishmentEventType.REVOKED, null,
                "first", staff, now.plusSeconds(1), null, PunishmentTxId.random(), "TEST"));

        PendingPunishmentEvent again = new PendingPunishmentEvent(issued.id(), p, PunishmentEventType.REVOKED,
                null, "second", staff, now.plusSeconds(2), null, PunishmentTxId.random(), "TEST");
        assertThatThrownBy(() -> punishments.revoke(again)).isInstanceOf(PunishmentConflictException.class);
    }
}
