package com.mcplatform.application.economy.port;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Outbound port for player master data (state-stored CRUD; implemented by infra-persistence). */
public interface PlayerRepository {

    /** A (uuid, name) pair from a name search — display data for picking a player in the web UI. */
    record PlayerNameMatch(UUID uuid, String name) {}

    /** Insert or update the player row, refreshing the cached name and last_seen. */
    void save(PlayerId player, String name, Instant seenAt);

    /**
     * Resolve a current Minecraft name to a UUID, case-insensitively ({@code LOWER(name)}). On ambiguity
     * (a name reused across players over time) the row with the most recent {@code last_seen} wins — the
     * rule fixed in the web-auth bridge, implemented here for the login slice.
     */
    Optional<PlayerId> findUuidByName(String name);

    /** The player's current cached name, or empty if no player row exists for this UUID yet. */
    Optional<String> findNameByUuid(PlayerId player);

    /**
     * Resolve many UUIDs to their cached names in one query (for display, e.g. grant issuers). UUIDs
     * without a player row are simply absent from the result map. Empty input → empty map.
     */
    Map<UUID, String> findNamesByUuids(Collection<UUID> uuids);

    /**
     * Case-insensitive prefix search on the cached name (uses {@code idx_player_name_lower}), ordered by
     * name, capped at {@code limit}. For picking a player in the web management UI. LIKE wildcards in the
     * query are treated as literals.
     */
    List<PlayerNameMatch> searchByNamePrefix(String prefix, int limit);

    /**
     * Atomic, idempotent session-join upsert: insert the player (first_seen/created_at default to now),
     * or on conflict refresh name, name_updated_at and last_seen. Returns {@code true} iff this call
     * created the row — the single source of truth for "is this player new?", safe under concurrent
     * joins from multiple nodes (exactly one INSERT wins; the rest are updates).
     */
    boolean upsertReturningWhetherNew(PlayerId player, String name, Instant seenAt);
}
