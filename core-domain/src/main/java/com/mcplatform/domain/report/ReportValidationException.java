package com.mcplatform.domain.report;

/** Raised when a report is semantically invalid (self-report, blank/too-long detail, oversized chat,
 * unknown category/player). Maps to HTTP 422. */
public final class ReportValidationException extends RuntimeException {

    public ReportValidationException(String message) {
        super(message);
    }
}
