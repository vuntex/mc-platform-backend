package com.mcplatform.protocol.punishment;

import java.util.UUID;

/**
 * Request body to revoke a punishment before its natural expiry. {@code transactionId} optional
 * (idempotency); {@code source} optional. Pure data (JDK only).
 */
public record RevokeRequest(
        UUID revokedBy,
        String reason,
        UUID transactionId,
        String source) {
}
