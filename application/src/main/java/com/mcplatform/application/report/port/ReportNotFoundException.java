package com.mcplatform.application.report.port;

import com.mcplatform.domain.report.ReportId;

/** Raised when a status change targets a report id that does not exist. Maps to HTTP 404. */
public final class ReportNotFoundException extends RuntimeException {

    public ReportNotFoundException(ReportId id) {
        super("report not found: " + id.value());
    }
}
