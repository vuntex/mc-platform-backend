package com.mcplatform.application.report.port;

import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.report.Report;
import com.mcplatform.domain.report.ReportId;
import com.mcplatform.domain.report.ReportStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for report persistence (state-stored, not event-sourced). Implemented by the jOOQ
 * adapter.
 */
public interface ReportRepository {

    /**
     * Persists a new {@code OPEN} report (report row + chat snapshot + initial status-history row) in one
     * transaction. If a concurrent insert wins the open-report uniqueness race for the same
     * (reporter, target), returns the existing open report instead (idempotent dedupe, FR-004).
     */
    Report create(Report report);

    /** The currently-open report for this (reporter, target) pair, if any — used for dedupe (FR-004). */
    Optional<Report> findOpenFor(PlayerId reporter, PlayerId target);

    /** Creation time of this reporter's most recent report, for cooldown enforcement (FR-005). */
    Optional<Instant> lastCreatedAtByReporter(PlayerId reporter);

    /** All non-terminal reports (OPEN + IN_PROGRESS), oldest first — the team's open work list (FR-008). */
    List<Report> findOpen();

    /** A single report by id (with its chat snapshot), if present. */
    Optional<Report> find(ReportId id);

    /**
     * Persists a status transition under optimistic locking on {@code expectedVersion}, appending a
     * status-history row ({@code previousStatus} → new). Returns the report with its bumped version.
     *
     * @throws ReportConflictException if a concurrent change moved the version (FR-014)
     */
    Report changeStatus(Report transitioned, ReportStatus previousStatus, long expectedVersion);
}
