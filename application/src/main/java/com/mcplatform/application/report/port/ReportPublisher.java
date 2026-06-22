package com.mcplatform.application.report.port;

import com.mcplatform.domain.report.ReportChange;

/**
 * Outbound port for publishing a live report-change notification (create / status change). The concrete
 * Redis Pub/Sub adapter lives in the composition root. Best-effort: a publish failure must never fail the
 * write (Postgres is the source of truth).
 */
@FunctionalInterface
public interface ReportPublisher {

    void publish(ReportChange change);
}
