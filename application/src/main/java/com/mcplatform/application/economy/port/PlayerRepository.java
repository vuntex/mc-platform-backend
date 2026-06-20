package com.mcplatform.application.economy.port;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;

/** Outbound port for player master data (state-stored CRUD; implemented by infra-persistence). */
public interface PlayerRepository {

    /** Insert or update the player row, refreshing the cached name and last_seen. */
    void save(PlayerId player, String name, Instant seenAt);

    /**
     * Atomic, idempotent session-join upsert: insert the player (first_seen/created_at default to now),
     * or on conflict refresh name, name_updated_at and last_seen. Returns {@code true} iff this call
     * created the row — the single source of truth for "is this player new?", safe under concurrent
     * joins from multiple nodes (exactly one INSERT wins; the rest are updates).
     */
    boolean upsertReturningWhetherNew(PlayerId player, String name, Instant seenAt);
}
