package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.PLAYER;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    @Override
    public Optional<PlayerId> findUuidByName(String name) {
        // LOWER(name) matches idx_player_name_lower; on ambiguity the most recent last_seen wins
        // (the rule fixed in the web-auth bridge).
        UUID uuid = dsl.select(PLAYER.UUID)
                .from(PLAYER)
                .where(DSL.lower(PLAYER.NAME).eq(name.toLowerCase(Locale.ROOT)))
                .orderBy(PLAYER.LAST_SEEN.desc())
                .limit(1)
                .fetchOne(PLAYER.UUID);
        return Optional.ofNullable(uuid).map(PlayerId::of);
    }

    @Override
    public Optional<String> findNameByUuid(PlayerId player) {
        return Optional.ofNullable(
                dsl.select(PLAYER.NAME).from(PLAYER).where(PLAYER.UUID.eq(player.value())).fetchOne(PLAYER.NAME));
    }

    @Override
    public Map<UUID, String> findNamesByUuids(Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return Map.of();
        }
        return dsl.select(PLAYER.UUID, PLAYER.NAME)
                .from(PLAYER)
                .where(PLAYER.UUID.in(uuids))
                .fetchMap(PLAYER.UUID, PLAYER.NAME);
    }

    @Override
    public List<PlayerNameMatch> searchByNamePrefix(String prefix, int limit) {
        // Escape LIKE metacharacters in the user input so they match literally; '\' is the escape char.
        String escaped = prefix.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return dsl.select(PLAYER.UUID, PLAYER.NAME)
                .from(PLAYER)
                .where(DSL.lower(PLAYER.NAME).like(escaped + "%", '\\'))
                .orderBy(PLAYER.NAME.asc())
                .limit(limit)
                .fetch(r -> new PlayerNameMatch(r.value1(), r.value2()));
    }
}
