package com.mcplatform.protocol.report;

import java.util.Objects;
import java.util.UUID;

/**
 * Pub/Sub event published whenever a report changes (created or status changed) — the shared contract
 * between backend and plugin for live team notification. Pure data (JDK only); {@code category},
 * {@code status} and {@code changeType} are Strings mirroring the domain enums. Deliberately carries NO
 * chat context (FR-015). Wire format lives in {@link ReportChangedEventCodec}.
 */
public record ReportChangedEvent(
        UUID reportId,
        UUID reporter,
        UUID target,
        String category,
        String status,
        String changeType,            // CREATED | STATUS_CHANGED
        long timestampEpochMilli) {

    public ReportChangedEvent {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(changeType, "changeType");
    }
}
