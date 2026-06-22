package com.mcplatform.application.permission;

import com.mcplatform.application.permission.port.ExpiredGrant;
import com.mcplatform.application.permission.port.GrantAuditPort;
import com.mcplatform.application.permission.port.GrantType;
import com.mcplatform.application.permission.port.PermissionChangePublisher;
import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.domain.permission.PermissionChangeType;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The live-expiry mechanism (FR-020). Periodically invoked from the composition root's scheduler: finds
 * grants whose {@code expiresAt} has passed while still active, deactivates each, writes a
 * {@code grant_audit(EXPIRE)} with the configured SYSTEM sentinel actor (FR-016a), and publishes ONE
 * change event per affected player UUID (not per grant). Resolution correctness does not depend on this
 * sweep — the resolver already filters by {@code now()}; the sweep delivers the live push + housekeeping.
 */
public final class GrantExpiryService {

    private static final Logger LOG = System.getLogger(GrantExpiryService.class.getName());

    private final PlayerGrantRepository grants;
    private final GrantAuditPort audit;
    private final PermissionChangePublisher publisher;
    private final Clock clock;
    private final UUID systemActor;

    public GrantExpiryService(PlayerGrantRepository grants, GrantAuditPort audit,
            PermissionChangePublisher publisher, Clock clock, UUID systemActor) {
        this.grants = grants;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
        this.systemActor = systemActor;
    }

    /** Sweeps expired grants. Returns the number of grants deactivated. */
    public int sweep() {
        Instant now = clock.instant();
        List<ExpiredGrant> expired = grants.findExpired(now);
        Set<UUID> affected = new LinkedHashSet<>();
        for (ExpiredGrant g : expired) {
            grants.deactivate(g);
            if (g.type() == GrantType.ROLE) {
                audit.record(GrantAuditPort.Entry.role(GrantAuditPort.Action.EXPIRE, g.player(), g.role(),
                        systemActor, null, now));
            } else {
                audit.record(GrantAuditPort.Entry.permission(GrantAuditPort.Action.EXPIRE, g.player(),
                        g.permission(), systemActor, null, now));
            }
            affected.add(g.player().value());
        }
        for (UUID player : affected) {
            safePublish(player);
        }
        return expired.size();
    }

    private void safePublish(UUID player) {
        try {
            publisher.publish(player, PermissionChangeType.GRANT_EXPIRED);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "permission expiry publish failed (non-fatal)", e);
        }
    }
}
