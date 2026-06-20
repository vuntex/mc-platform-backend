package com.mcplatform.protocol.punishment;

import java.util.UUID;

/**
 * Request body to issue a punishment from a template. The template pre-fills type/reason/duration;
 * {@code reason} optionally overrides the template's default reason (null/blank → template default).
 * {@code transactionId} optional (idempotency); {@code source} optional. Pure data (JDK only).
 */
public record IssueFromTemplateRequest(
        String templateKey,
        String reason,         // optional override of the template's default reason
        UUID issuedBy,
        UUID transactionId,
        String source) {
}
