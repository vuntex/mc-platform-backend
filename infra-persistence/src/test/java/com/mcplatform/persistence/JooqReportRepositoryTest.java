package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.REPORT_STATUS_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.report.port.ReportConflictException;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.report.ChatContext;
import com.mcplatform.domain.report.ChatContextEntry;
import com.mcplatform.domain.report.Report;
import com.mcplatform.domain.report.ReportCategory;
import com.mcplatform.domain.report.ReportId;
import com.mcplatform.domain.report.ReportStatus;
import com.mcplatform.domain.report.ReportValidationException;
import java.time.Instant;
import java.util.List;
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

/** Integration test for the jOOQ report adapter against a real, Flyway-migrated Postgres. */
@Testcontainers
class JooqReportRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqReportRepository reports;
    static JooqPlayerRepository players;

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        reports = new JooqReportRepository(dsl);
        players = new JooqPlayerRepository(dsl);
    }

    private PlayerId newPlayer() {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, "Steve", Instant.now());
        return p;
    }

    private Report newReport(PlayerId reporter, PlayerId target, ChatContext chat) {
        return Report.create(ReportId.random(), reporter, target, ReportCategory.CHEATING, "flying", chat,
                Instant.now());
    }

    @Test
    void createPersistsReportHistoryAndChatSnapshot() {
        PlayerId reporter = newPlayer();
        PlayerId target = newPlayer();
        PlayerId chatter = newPlayer();
        ChatContext chat = new ChatContext(List.of(
                new ChatContextEntry(target, "lol ez", Instant.now()),
                new ChatContextEntry(chatter, "report him", Instant.now())));

        Report created = reports.create(newReport(reporter, target, chat));

        // chat snapshot round-trips unchanged, in order
        Report loaded = reports.findOpenFor(reporter, target).orElseThrow();
        assertThat(loaded.id()).isEqualTo(created.id());
        assertThat(loaded.chatContext().entries()).extracting(ChatContextEntry::text)
                .containsExactly("lol ez", "report him");

        // one status-history row recorded for the creation
        long historyRows = dsl.fetchCount(REPORT_STATUS_HISTORY,
                REPORT_STATUS_HISTORY.REPORT_ID.eq(created.id().value()));
        assertThat(historyRows).isEqualTo(1);
    }

    @Test
    void secondOpenReportForSamePairIsDeduped() {
        PlayerId reporter = newPlayer();
        PlayerId target = newPlayer();

        Report first = reports.create(newReport(reporter, target, ChatContext.EMPTY));
        Report second = reports.create(newReport(reporter, target, ChatContext.EMPTY));

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void unknownTargetIsRejectedAsValidation() {
        PlayerId reporter = newPlayer();
        PlayerId unknownTarget = PlayerId.of(UUID.randomUUID()); // never saved → FK violation

        assertThatThrownBy(() -> reports.create(newReport(reporter, unknownTarget, ChatContext.EMPTY)))
                .isInstanceOf(ReportValidationException.class);
    }

    @Test
    void lastCreatedAtTracksTheReportersMostRecentReport() {
        PlayerId reporter = newPlayer();
        assertThat(reports.lastCreatedAtByReporter(reporter)).isEmpty();

        reports.create(newReport(reporter, newPlayer(), ChatContext.EMPTY));
        assertThat(reports.lastCreatedAtByReporter(reporter)).isPresent();
    }

    @Test
    void findOpenReturnsNonTerminalReports() {
        Report a = reports.create(newReport(newPlayer(), newPlayer(), ChatContext.EMPTY));
        Report b = reports.create(newReport(newPlayer(), newPlayer(), ChatContext.EMPTY));

        assertThat(reports.findOpen()).extracting(Report::id).contains(a.id(), b.id());
    }

    @Test
    void changeStatusUpdatesStateAndAppendsHistory() {
        PlayerId handler = newPlayer();
        Report created = reports.create(newReport(newPlayer(), newPlayer(), ChatContext.EMPTY));

        Report transitioned = created.transitionTo(ReportStatus.IN_PROGRESS, handler, Instant.now());
        Report saved = reports.changeStatus(transitioned, created.status(), created.version());

        assertThat(saved.status()).isEqualTo(ReportStatus.IN_PROGRESS);
        assertThat(saved.lastHandledBy()).isEqualTo(handler);
        assertThat(saved.version()).isEqualTo(created.version() + 1);

        Report reloaded = reports.find(created.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(ReportStatus.IN_PROGRESS);
        // creation row + transition row
        long history = dsl.fetchCount(REPORT_STATUS_HISTORY, REPORT_STATUS_HISTORY.REPORT_ID.eq(created.id().value()));
        assertThat(history).isEqualTo(2);
    }

    @Test
    void changeStatusWithStaleVersionConflicts() {
        PlayerId handler = newPlayer();
        Report created = reports.create(newReport(newPlayer(), newPlayer(), ChatContext.EMPTY));
        Report transitioned = created.transitionTo(ReportStatus.IN_PROGRESS, handler, Instant.now());

        assertThatThrownBy(() -> reports.changeStatus(transitioned, created.status(), created.version() + 99))
                .isInstanceOf(ReportConflictException.class);
    }
}
