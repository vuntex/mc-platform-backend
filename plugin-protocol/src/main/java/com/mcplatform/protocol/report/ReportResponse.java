package com.mcplatform.protocol.report;

import java.util.List;
import java.util.UUID;

/**
 * Response body for a single report — shared REST contract (JDK only). Epoch-milli {@code 0} encodes
 * "no status change yet" for {@code lastStatusChangeAtEpochMilli}; {@code lastHandledBy} is null until
 * the first transition. {@code chatContext} is the stored snapshot (possibly empty).
 */
public record ReportResponse(
        UUID id,
        UUID reporter,
        UUID target,
        String category,
        String detail,
        String status,
        long createdAtEpochMilli,
        UUID lastHandledBy,                 // null until first status change
        long lastStatusChangeAtEpochMilli,  // 0 until first status change
        List<ChatMessage> chatContext,
        long version) {
}
