package com.mcplatform.domain.report;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;

/**
 * A report — an accusation by one player against another. State-stored (not event-sourced): the row is
 * the truth, with {@code report_status_history} carrying the audit trail. A report never produces a
 * punishment (Constitution principle 16). {@code version} backs optimistic locking on status changes.
 */
public record Report(
        ReportId id,
        PlayerId reporter,
        PlayerId target,
        ReportCategory category,
        String detail,
        ChatContext chatContext,
        ReportStatus status,
        Instant createdAt,
        PlayerId lastHandledBy,        // null until the first status change
        Instant lastStatusChangeAt,    // null until the first status change
        long version) {

    public static final int MAX_DETAIL_LENGTH = 256;

    public Report {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(chatContext, "chatContext");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Factory for a brand-new {@code OPEN} report. Enforces the no-self-report rule (FR-003) and the
     * detail bounds (FR-007); the detail is stripped of surrounding whitespace.
     */
    public static Report create(ReportId id, PlayerId reporter, PlayerId target, ReportCategory category,
            String detail, ChatContext chatContext, Instant createdAt) {
        if (reporter.equals(target)) {
            throw new ReportValidationException("a player cannot report themselves");
        }
        String trimmed = detail == null ? "" : detail.strip();
        if (trimmed.isEmpty()) {
            throw new ReportValidationException("detail must not be blank");
        }
        if (trimmed.length() > MAX_DETAIL_LENGTH) {
            throw new ReportValidationException("detail exceeds " + MAX_DETAIL_LENGTH + " chars");
        }
        return new Report(id, reporter, target, category, trimmed,
                chatContext == null ? ChatContext.EMPTY : chatContext,
                ReportStatus.OPEN, createdAt, null, null, 0);
    }

    /**
     * Returns a copy moved to {@code newStatus}, recording the handling staff and time. Throws
     * {@link InvalidStatusTransitionException} if the transition is not allowed (FR-010). {@code version}
     * is unchanged here — persistence increments it under the optimistic-lock UPDATE.
     */
    public Report transitionTo(ReportStatus newStatus, PlayerId handler, Instant at) {
        if (!status.canTransitionTo(newStatus)) {
            throw new InvalidStatusTransitionException(status, newStatus);
        }
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(at, "at");
        return new Report(id, reporter, target, category, detail, chatContext,
                newStatus, createdAt, handler, at, version);
    }
}
