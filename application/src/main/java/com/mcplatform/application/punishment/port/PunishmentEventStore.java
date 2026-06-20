package com.mcplatform.application.punishment.port;

import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.punishment.PendingPunishmentEvent;
import com.mcplatform.domain.punishment.Punishment;
import com.mcplatform.domain.punishment.PunishmentId;
import com.mcplatform.domain.punishment.PunishmentTxId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port to the event-sourced punishment store (implemented by infra-persistence). Writes
 * insert the event AND project the {@code punishment} row in ONE transaction; {@link #issue} also
 * enforces the exclusive-category invariant under a per-player lock as the concurrency backstop to the
 * domain {@code PunishmentPolicy}.
 */
public interface PunishmentEventStore {

    /** Punishments that are active for the player at {@code now} (issued, not revoked, not expired). */
    List<Punishment> activeForPlayer(PlayerId player, Instant now);

    /** Current projected state of a single punishment, if it exists. */
    Optional<Punishment> find(PunishmentId id);

    /**
     * Resolve the punishment whose event carried {@code transactionId} (for idempotent replay): returns
     * the resulting punishment state, or empty if that transaction id was never written.
     */
    Optional<Punishment> findByTransactionId(PunishmentTxId transactionId);

    /**
     * Append an {@code ISSUED} event and project it. For an exclusive category, the projection is
     * guarded under a per-player lock that re-checks "no other active punishment in this category at
     * {@code now}" — throwing {@code PunishmentConflictException} on violation. Idempotent on the
     * event's transaction id (a replay returns the recorded punishment).
     */
    Punishment issue(PendingPunishmentEvent event, Instant now);

    /**
     * Append a {@code REVOKED} event and project it onto the existing punishment.
     *
     * @throws PunishmentNotFoundException if no punishment with that id exists
     */
    Punishment revoke(PendingPunishmentEvent event);
}
