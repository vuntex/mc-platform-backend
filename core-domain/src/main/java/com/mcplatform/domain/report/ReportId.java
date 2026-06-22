package com.mcplatform.domain.report;

import java.util.Objects;
import java.util.UUID;

/** Identity of a single report aggregate. */
public record ReportId(UUID value) {

    public ReportId {
        Objects.requireNonNull(value, "report id must not be null");
    }

    public static ReportId of(UUID value) {
        return new ReportId(value);
    }

    public static ReportId random() {
        return new ReportId(UUID.randomUUID());
    }
}
