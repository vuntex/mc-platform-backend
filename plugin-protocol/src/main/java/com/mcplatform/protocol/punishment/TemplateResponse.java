package com.mcplatform.protocol.punishment;

/**
 * Response body for a punishment template. {@code canApply} tells the querying team member whether
 * they hold {@code requiredPermission} — computed backend-side, so the UI can show all templates and
 * disable the ones the member may not apply. {@code durationMillis} is {@code 0} for non-time-bound
 * types. Pure data (JDK only).
 */
public record TemplateResponse(
        String key,
        String type,
        String defaultReason,
        long durationMillis,   // 0 = none (WARN, PERMABAN)
        String requiredPermission,
        boolean active,
        boolean canApply) {
}
