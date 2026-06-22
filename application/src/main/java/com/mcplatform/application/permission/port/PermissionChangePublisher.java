package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.PermissionChangeType;
import java.util.UUID;

/**
 * Outbound port for live permission-change notification (FR-020/FR-021). Takes the DOMAIN
 * {@link PermissionChangeType} so the application layer stays {@code plugin-protocol}-free — the
 * composition-root adapter bridges it onto Redis Pub/Sub ({@code mc:permission:changed}), mirroring
 * {@code ReportPublisher}. Best-effort: a failure here must never fail the originating operation.
 */
public interface PermissionChangePublisher {

    void publish(UUID player, PermissionChangeType type);
}
