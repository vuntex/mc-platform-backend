package com.mcplatform.protocol.punishment;

import java.util.UUID;

/**
 * Response body for a single punishment — the shared REST contract between backend and plugin/web.
 * Pure data (JDK only). Epoch-milli {@code 0} encodes "none" for the optional {@code expiresAt} /
 * {@code revokedAt}; {@code revokedBy} is {@code null} when not revoked. {@code active} is computed
 * backend-side at response time.
 */
public record PunishmentResponse(
        UUID id,
        UUID playerUuid,
        String type,
        String reason,
        UUID issuedBy,
        long issuedAtEpochMilli,
        long expiresAtEpochMilli,   // 0 = permanent / not applicable
        UUID revokedBy,             // null if not revoked
        long revokedAtEpochMilli,   // 0 = not revoked
        boolean active,
        long version) {
}
