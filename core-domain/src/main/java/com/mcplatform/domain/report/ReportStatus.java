package com.mcplatform.domain.report;

/**
 * Lifecycle of a report. Canonical (English) values are what is persisted/transported; the spec's German
 * labels (OFFEN/IN_BEARBEITUNG/ERLEDIGT/ABGELEHNT) are display-only. Allowed transitions:
 * OPEN→IN_PROGRESS, OPEN→REJECTED, IN_PROGRESS→RESOLVED, IN_PROGRESS→REJECTED. RESOLVED/REJECTED are
 * terminal. The transition rule is the domain's authority (FR-010).
 */
public enum ReportStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    REJECTED;

    /** The two non-terminal states that make up the team's "open" work list (FR-008/013). */
    public boolean isOpen() {
        return this == OPEN || this == IN_PROGRESS;
    }

    public boolean isTerminal() {
        return this == RESOLVED || this == REJECTED;
    }

    /** Whether moving from this status to {@code target} is one of the allowed transitions. */
    public boolean canTransitionTo(ReportStatus target) {
        return switch (this) {
            case OPEN -> target == IN_PROGRESS || target == REJECTED;
            case IN_PROGRESS -> target == RESOLVED || target == REJECTED;
            case RESOLVED, REJECTED -> false;
        };
    }
}
