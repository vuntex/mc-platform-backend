package com.mcplatform.protocol.punishment;

import java.util.UUID;

/**
 * Request body to directly issue a punishment. {@code durationMillis} is required for time-bound types
 * (TEMPBAN, CHATBAN) and ignored for the rest; pass {@code null} otherwise. {@code transactionId} is
 * optional — pass a stable id to make the issue idempotent across retries, omit it (null) to have the
 * server generate one. {@code source} is optional (null/blank → server default). Pure data (JDK only).
 */
public record IssueRequest(
        String type,
        String reason,
        Long durationMillis,   // null for WARN/PERMABAN
        UUID issuedBy,
        UUID transactionId,
        String source) {
}
