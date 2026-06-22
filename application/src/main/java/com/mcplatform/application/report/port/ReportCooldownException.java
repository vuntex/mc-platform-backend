package com.mcplatform.application.report.port;

/** Raised when a reporter creates reports faster than the configured cooldown allows (FR-005). Maps to HTTP 429. */
public final class ReportCooldownException extends RuntimeException {

    public ReportCooldownException(String message) {
        super(message);
    }
}
