package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.PLAYER;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** jOOQ adapter for player master data (state-stored upsert). */
public final class JooqPlayerRepository implements PlayerRepository {

    private final DSLContext dsl;

    public JooqPlayerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void save(PlayerId player, String name, Instant seenAt) {
        upsertReturningWhetherNew(player, name, seenAt);
    }

    @Override
    public boolean upsertReturningWhetherNew(PlayerId player, String name, Instant seenAt) {
        OffsetDateTime ts = seenAt.atOffset(ZoneOffset.UTC);
        // RETURNING (xmax = 0): on a fresh INSERT xmax is 0 -> true; on ON CONFLICT DO UPDATE the row
        // carries the updating xid -> false. first_seen/created_at fall back to their now() defaults.
        Boolean inserted = dsl.insertInto(PLAYER)
                .set(PLAYER.UUID, player.value())
                .set(PLAYER.NAME, name)
                .set(PLAYER.NAME_UPDATED_AT, ts)
                .set(PLAYER.LAST_SEEN, ts)
                .onConflict(PLAYER.UUID)
                .doUpdate()
                .set(PLAYER.NAME, name)
                .set(PLAYER.NAME_UPDATED_AT, ts)
                .set(PLAYER.LAST_SEEN, ts)
                .returningResult(DSL.field("xmax = 0", Boolean.class))
                .fetchOne()
                .value1();
        return Boolean.TRUE.equals(inserted);
    }
}
