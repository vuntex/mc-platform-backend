package com.mcplatform.domain.punishment;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The coexistence rule, as a pure domain function so it is unit-testable without a database. Given the
 * player's currently active punishments, decides whether a new one may be issued:
 *
 * <ul>
 *   <li>exclusive categories (CHAT, ACCESS) admit at most ONE active punishment — a second is rejected
 *       (so a Chatban + a Ban may coexist, but never two active Bans nor two active Chatbans);</li>
 *   <li>the non-exclusive NOTICE category (WARN) accumulates without limit.</li>
 * </ul>
 *
 * <p>This is the fast, authoritative rule used on the happy path; the persistence adapter re-checks it
 * under a per-player lock as the concurrency backstop, since the rule depends on {@code now()} and a
 * static unique index cannot express time-based expiry.
 */
public final class PunishmentPolicy {

    private PunishmentPolicy() {}

    /**
     * Builds the {@code ISSUED} event for a new punishment, throwing {@link PunishmentConflictException}
     * if an exclusive-category punishment is already active for the player at {@code now}.
     */
    public static PendingPunishmentEvent issue(
            List<Punishment> activePunishments,
            PunishmentId id,
            PlayerId player,
            PunishmentType type,
            String reason,
            PlayerId issuedBy,
            Instant now,
            Instant expiresAt,
            PunishmentTxId transactionId,
            String source) {
        Objects.requireNonNull(activePunishments, "activePunishments");
        Objects.requireNonNull(type, "type");
        if (type.category().isExclusive()) {
            boolean clash = activePunishments.stream()
                    .anyMatch(p -> p.isActive(now) && p.category() == type.category());
            if (clash) {
                throw new PunishmentConflictException(player, type.category());
            }
        }
        return new PendingPunishmentEvent(
                id, player, PunishmentEventType.ISSUED, type, reason, issuedBy, now, expiresAt,
                transactionId, source);
    }
}
