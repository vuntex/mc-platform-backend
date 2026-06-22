package com.mcplatform.application.report;

import com.mcplatform.application.report.port.ReportCooldownException;
import com.mcplatform.application.report.port.ReportNotFoundException;
import com.mcplatform.application.report.port.ReportPublisher;
import com.mcplatform.application.report.port.ReportRepository;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.report.ChatContext;
import com.mcplatform.domain.report.Report;
import com.mcplatform.domain.report.ReportCategory;
import com.mcplatform.domain.report.ReportChange;
import com.mcplatform.domain.report.ReportId;
import com.mcplatform.domain.report.ReportStatus;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application use case for reports. Creating is open to any player (no permission gate); viewing/handling
 * are backend-authoritative via {@link PermissionResolver} (added in later slices). A report is a pure
 * accusation — it never produces a punishment (Constitution principle 16).
 *
 * <p>Create flow (FR-001/003/004/005): dedupe first (an existing open report for the same
 * reporter+target is returned as-is, idempotent), otherwise enforce the per-reporter cooldown, then
 * persist and publish the live change. Publishing is best-effort after the write (mirrors
 * {@code PunishmentService}).
 */
public final class ReportService {

    private static final Logger LOG = System.getLogger(ReportService.class.getName());

    /** Permission to view the open report list. */
    public static final String VIEW_PERMISSION = "report.view";
    /** Permission to change a report's status. */
    public static final String HANDLE_PERMISSION = "report.handle";

    private final ReportRepository reports;
    private final PermissionResolver permissions;
    private final ReportPublisher publisher;
    private final Clock clock;
    private final Duration cooldown;

    public ReportService(ReportRepository reports, PermissionResolver permissions, ReportPublisher publisher,
            Clock clock, Duration cooldown) {
        this.reports = reports;
        this.permissions = permissions;
        this.publisher = publisher;
        this.clock = clock;
        this.cooldown = cooldown;
    }

    /**
     * Create a report. Returns the existing open report unchanged if the reporter already has one open
     * against this target (dedupe). Otherwise enforces the cooldown, persists, and publishes a CREATED
     * change.
     */
    public Report create(PlayerId reporter, PlayerId target, ReportCategory category, String detail,
            ChatContext chatContext) {
        Instant now = clock.instant();

        Optional<Report> existing = reports.findOpenFor(reporter, target);
        if (existing.isPresent()) {
            return existing.get();
        }
        enforceCooldown(reporter, now);

        Report report = Report.create(ReportId.random(), reporter, target, category, detail, chatContext, now);
        Report stored = reports.create(report);
        safePublish(ReportChange.created(stored, now));
        return stored;
    }

    /** The open report list (OPEN + IN_PROGRESS). Requires {@link #VIEW_PERMISSION} (backend-authoritative). */
    public List<Report> listOpen(PlayerId viewer) {
        requirePermission(viewer, VIEW_PERMISSION);
        return reports.findOpen();
    }

    /**
     * Move a report to {@code newStatus}, recording the handling team member + time. Requires
     * {@link #HANDLE_PERMISSION}. Throws on unknown id (404), illegal transition (409 via the domain) or
     * a concurrent modification (409).
     */
    public Report changeStatus(ReportId id, ReportStatus newStatus, PlayerId handler) {
        requirePermission(handler, HANDLE_PERMISSION);
        Instant now = clock.instant();
        Report current = reports.find(id).orElseThrow(() -> new ReportNotFoundException(id));
        Report transitioned = current.transitionTo(newStatus, handler, now);
        Report saved = reports.changeStatus(transitioned, current.status(), current.version());
        safePublish(ReportChange.statusChanged(saved, now));
        return saved;
    }

    private void requirePermission(PlayerId actor, String permission) {
        if (!permissions.hasPermission(actor.value(), permission)) {
            throw new PermissionDeniedException(actor.value(), permission);
        }
    }

    private void enforceCooldown(PlayerId reporter, Instant now) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return;
        }
        reports.lastCreatedAtByReporter(reporter).ifPresent(last -> {
            if (now.isBefore(last.plus(cooldown))) {
                throw new ReportCooldownException(
                        "report cooldown active; wait before creating another report");
            }
        });
    }

    private void safePublish(ReportChange change) {
        try {
            publisher.publish(change);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "report change publish failed (non-fatal)", e);
        }
    }
}
