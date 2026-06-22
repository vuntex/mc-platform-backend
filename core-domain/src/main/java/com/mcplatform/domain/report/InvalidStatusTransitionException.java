package com.mcplatform.domain.report;

/** Raised when a report status change is not one of the allowed transitions (FR-010). Maps to HTTP 409. */
public final class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(ReportStatus from, ReportStatus to) {
        super("illegal report status transition: " + from + " -> " + to);
    }
}
