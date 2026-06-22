package com.mcplatform.application.report.port;

/** Raised when a report status change loses the optimistic-lock race (concurrent modification, FR-014).
 * Maps to HTTP 409. */
public final class ReportConflictException extends RuntimeException {

    public ReportConflictException(String message) {
        super(message);
    }
}
