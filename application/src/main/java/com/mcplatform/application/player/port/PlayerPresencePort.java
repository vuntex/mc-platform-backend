package com.mcplatform.application.player.port;

import com.mcplatform.domain.player.PlayerId;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port for live player presence — "who is currently connected" — backed by a fast, ephemeral
 * store (Redis SET in infra-cache). The backend is authoritative: the plugin reports join/leave over
 * REST, this port records it. Reads are used by the web dashboard (online flag + count).
 *
 * <p>Implementations are expected to degrade gracefully when the backing store is unavailable (presence
 * is a soft signal, never a correctness boundary): reads yield empty/0, writes are best-effort.
 */
public interface PlayerPresencePort {

    /** Record the player as online (idempotent). */
    void markOnline(PlayerId player);

    /** Record the player as offline (idempotent; no-op if not present). */
    void markOffline(PlayerId player);

    /** Whether the player is currently flagged online. */
    boolean isOnline(PlayerId player);

    /** UUIDs of all currently online players (empty if none / store unavailable). */
    Set<UUID> onlinePlayers();

    /** Number of currently online players (0 if none / store unavailable). */
    long onlineCount();
}
