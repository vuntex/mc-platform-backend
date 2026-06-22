package com.mcplatform.application.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.report.port.ReportConflictException;
import com.mcplatform.application.report.port.ReportCooldownException;
import com.mcplatform.application.report.port.ReportNotFoundException;
import com.mcplatform.application.report.port.ReportPublisher;
import com.mcplatform.application.report.port.ReportRepository;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.report.ChatContext;
import com.mcplatform.domain.report.InvalidStatusTransitionException;
import com.mcplatform.domain.report.Report;
import com.mcplatform.domain.report.ReportCategory;
import com.mcplatform.domain.report.ReportChange;
import com.mcplatform.domain.report.ReportId;
import com.mcplatform.domain.report.ReportStatus;
import com.mcplatform.domain.report.ReportValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

    private final PlayerId reporter = PlayerId.of(UUID.randomUUID());
    private final PlayerId target = PlayerId.of(UUID.randomUUID());
    private final PlayerId staff = PlayerId.of(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-06-22T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private ReportService service(FakeReports reports, FakePermissions perms, FakePublisher publisher,
            Duration cooldown) {
        return new ReportService(reports, perms, publisher, clock, cooldown);
    }

    // --- create -----------------------------------------------------------

    @Test
    void createPersistsAndPublishesOnce() {
        FakeReports reports = new FakeReports();
        FakePublisher publisher = new FakePublisher();
        ReportService service = service(reports, new FakePermissions(), publisher, Duration.ofSeconds(60));

        Report r = service.create(reporter, target, ReportCategory.CHEATING, "flying", ChatContext.EMPTY);

        assertThat(r.reporter()).isEqualTo(reporter);
        assertThat(reports.creates).isEqualTo(1);
        assertThat(publisher.changes).singleElement()
                .satisfies(c -> assertThat(c.changeType()).isEqualTo(ReportChange.ChangeType.CREATED));
    }

    @Test
    void duplicateOpenReportReturnsExistingWithoutNewCreateOrPublish() {
        FakeReports reports = new FakeReports();
        Report existing = Report.create(ReportId.random(), reporter, target, ReportCategory.CHEATING,
                "first", ChatContext.EMPTY, now.minusSeconds(120));
        reports.seed(existing);
        FakePublisher publisher = new FakePublisher();
        ReportService service = service(reports, new FakePermissions(), publisher, Duration.ofSeconds(60));

        Report r = service.create(reporter, target, ReportCategory.BELEIDIGUNG, "second", ChatContext.EMPTY);

        assertThat(r.id()).isEqualTo(existing.id());
        assertThat(reports.creates).isZero();
        assertThat(publisher.changes).isEmpty();
    }

    @Test
    void cooldownBlocksASecondReportTooSoon() {
        FakeReports reports = new FakeReports();
        reports.seed(Report.create(ReportId.random(), reporter, PlayerId.of(UUID.randomUUID()),
                ReportCategory.SPAM_WERBUNG, "earlier", ChatContext.EMPTY, now));
        ReportService service = service(reports, new FakePermissions(), new FakePublisher(), Duration.ofSeconds(60));

        assertThatThrownBy(() -> service.create(reporter, target, ReportCategory.CHEATING, "x", ChatContext.EMPTY))
                .isInstanceOf(ReportCooldownException.class);
    }

    @Test
    void selfReportIsRejected() {
        ReportService service = service(new FakeReports(), new FakePermissions(), new FakePublisher(), Duration.ZERO);
        assertThatThrownBy(() -> service.create(reporter, reporter, ReportCategory.SONSTIGES, "x", ChatContext.EMPTY))
                .isInstanceOf(ReportValidationException.class);
    }

    // --- list open --------------------------------------------------------

    @Test
    void listOpenRequiresViewPermission() {
        ReportService service = service(new FakeReports(), new FakePermissions(), new FakePublisher(), Duration.ZERO);
        assertThatThrownBy(() -> service.listOpen(staff)).isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void listOpenReturnsOnlyNonTerminalReports() {
        FakeReports reports = new FakeReports();
        reports.seed(open(reporter, target, ReportStatus.OPEN));
        reports.seed(open(reporter, PlayerId.of(UUID.randomUUID()), ReportStatus.IN_PROGRESS));
        reports.seed(open(reporter, PlayerId.of(UUID.randomUUID()), ReportStatus.RESOLVED));
        ReportService service = service(reports,
                new FakePermissions().grant(staff, ReportService.VIEW_PERMISSION), new FakePublisher(), Duration.ZERO);

        List<Report> open = service.listOpen(staff);

        assertThat(open).hasSize(2);
        assertThat(open).allMatch(r -> r.status().isOpen());
    }

    // --- change status ----------------------------------------------------

    @Test
    void changeStatusRequiresHandlePermission() {
        FakeReports reports = new FakeReports();
        Report r = open(reporter, target, ReportStatus.OPEN);
        reports.seed(r);
        ReportService service = service(reports, new FakePermissions(), new FakePublisher(), Duration.ZERO);

        assertThatThrownBy(() -> service.changeStatus(r.id(), ReportStatus.IN_PROGRESS, staff))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void changeStatusRecordsHandlerAndPublishes() {
        FakeReports reports = new FakeReports();
        Report r = open(reporter, target, ReportStatus.OPEN);
        reports.seed(r);
        FakePublisher publisher = new FakePublisher();
        ReportService service = service(reports,
                new FakePermissions().grant(staff, ReportService.HANDLE_PERMISSION), publisher, Duration.ZERO);

        Report updated = service.changeStatus(r.id(), ReportStatus.IN_PROGRESS, staff);

        assertThat(updated.status()).isEqualTo(ReportStatus.IN_PROGRESS);
        assertThat(updated.lastHandledBy()).isEqualTo(staff);
        assertThat(updated.lastStatusChangeAt()).isEqualTo(now);
        assertThat(updated.version()).isEqualTo(r.version() + 1);
        assertThat(publisher.changes).singleElement()
                .satisfies(c -> assertThat(c.changeType()).isEqualTo(ReportChange.ChangeType.STATUS_CHANGED));
    }

    @Test
    void changeStatusRejectsIllegalTransition() {
        FakeReports reports = new FakeReports();
        Report r = open(reporter, target, ReportStatus.OPEN);
        reports.seed(r);
        ReportService service = service(reports,
                new FakePermissions().grant(staff, ReportService.HANDLE_PERMISSION), new FakePublisher(), Duration.ZERO);

        assertThatThrownBy(() -> service.changeStatus(r.id(), ReportStatus.RESOLVED, staff))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void changeStatusUnknownIdIsNotFound() {
        ReportService service = service(new FakeReports(),
                new FakePermissions().grant(staff, ReportService.HANDLE_PERMISSION), new FakePublisher(), Duration.ZERO);

        assertThatThrownBy(() -> service.changeStatus(ReportId.random(), ReportStatus.IN_PROGRESS, staff))
                .isInstanceOf(ReportNotFoundException.class);
    }

    // --- helpers ----------------------------------------------------------

    private Report open(PlayerId reporter, PlayerId target, ReportStatus status) {
        return new Report(ReportId.random(), reporter, target, ReportCategory.CHEATING, "detail",
                ChatContext.EMPTY, status, now.minusSeconds(300), null, null, 0);
    }

    private static final class FakeReports implements ReportRepository {
        final Map<UUID, Report> byId = new LinkedHashMap<>();
        int creates = 0;

        void seed(Report r) {
            byId.put(r.id().value(), r);
        }

        @Override
        public Report create(Report report) {
            creates++;
            byId.put(report.id().value(), report);
            return report;
        }

        @Override
        public Optional<Report> findOpenFor(PlayerId reporter, PlayerId target) {
            return byId.values().stream()
                    .filter(r -> r.reporter().equals(reporter) && r.target().equals(target) && r.status().isOpen())
                    .reduce((a, b) -> b);
        }

        @Override
        public Optional<Instant> lastCreatedAtByReporter(PlayerId reporter) {
            return byId.values().stream()
                    .filter(r -> r.reporter().equals(reporter))
                    .map(Report::createdAt)
                    .max(Comparator.naturalOrder());
        }

        @Override
        public List<Report> findOpen() {
            return byId.values().stream()
                    .filter(r -> r.status().isOpen())
                    .sorted(Comparator.comparing(Report::createdAt))
                    .toList();
        }

        @Override
        public Optional<Report> find(ReportId id) {
            return Optional.ofNullable(byId.get(id.value()));
        }

        @Override
        public Report changeStatus(Report transitioned, ReportStatus previousStatus, long expectedVersion) {
            Report current = byId.get(transitioned.id().value());
            if (current == null || current.version() != expectedVersion) {
                throw new ReportConflictException("conflict");
            }
            Report saved = new Report(transitioned.id(), transitioned.reporter(), transitioned.target(),
                    transitioned.category(), transitioned.detail(), transitioned.chatContext(),
                    transitioned.status(), transitioned.createdAt(), transitioned.lastHandledBy(),
                    transitioned.lastStatusChangeAt(), expectedVersion + 1);
            byId.put(saved.id().value(), saved);
            return saved;
        }
    }

    private static final class FakePermissions implements PermissionResolver {
        final Set<String> granted = new HashSet<>();

        FakePermissions grant(PlayerId who, String permission) {
            granted.add(who.value() + "|" + permission);
            return this;
        }

        @Override
        public boolean hasPermission(UUID staffUuid, String permission) {
            return granted.contains(staffUuid + "|" + permission);
        }
    }

    private static final class FakePublisher implements ReportPublisher {
        final List<ReportChange> changes = new ArrayList<>();

        @Override
        public void publish(ReportChange change) {
            changes.add(change);
        }
    }
}
