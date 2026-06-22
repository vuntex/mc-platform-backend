package com.mcplatform.domain.report;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;

/**
 * Lightweight notification of a report state change, handed to the {@code ReportPublisher} for the live
 * Pub/Sub update. Deliberately carries NO chat context (FR-015) — only identity, status and the
 * involved players — so the wire message stays small.
 */
public record ReportChange(
        ReportId reportId,
        PlayerId reporter,
        PlayerId target,
        ReportCategory category,
        ReportStatus status,
        ChangeType changeType,
        Instant occurredAt) {

    public enum ChangeType { CREATED, STATUS_CHANGED }

    public static ReportChange created(Report r, Instant at) {
        return new ReportChange(r.id(), r.reporter(), r.target(), r.category(), r.status(),
                ChangeType.CREATED, at);
    }

    public static ReportChange statusChanged(Report r, Instant at) {
        return new ReportChange(r.id(), r.reporter(), r.target(), r.category(), r.status(),
                ChangeType.STATUS_CHANGED, at);
    }
}
