package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.PLAYER_PERMISSION_GRANT;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_ROLE_GRANT;

import com.mcplatform.application.permission.port.ExpiredGrant;
import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * jOOQ adapter over {@code player_role_grant} / {@code player_permission_grant}. One row per
 * (player, role) and per (player, permission); a re-grant is an {@code ON CONFLICT DO UPDATE} upsert
 * (FR-014a). Revoke/expire flip the soft {@code active} flag. No Spring.
 */
public final class JooqPlayerGrantRepository implements PlayerGrantRepository {

    private final DSLContext dsl;

    public JooqPlayerGrantRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void upsertRoleGrant(RoleGrant g) {
        dsl.insertInto(PLAYER_ROLE_GRANT)
                .set(PLAYER_ROLE_GRANT.UUID, g.player().value())
                .set(PLAYER_ROLE_GRANT.ROLE_ID, g.role().value())
                .set(PLAYER_ROLE_GRANT.ISSUED_BY, g.issuedBy())
                .set(PLAYER_ROLE_GRANT.ISSUED_AT, offset(g.issuedAt()))
                .set(PLAYER_ROLE_GRANT.EXPIRES_AT, offset(g.expiresAt()))
                .set(PLAYER_ROLE_GRANT.REASON, g.reason())
                .set(PLAYER_ROLE_GRANT.ACTIVE, true)
                .onConflict(PLAYER_ROLE_GRANT.UUID, PLAYER_ROLE_GRANT.ROLE_ID)
                .doUpdate()
                .set(PLAYER_ROLE_GRANT.ISSUED_BY, g.issuedBy())
                .set(PLAYER_ROLE_GRANT.ISSUED_AT, offset(g.issuedAt()))
                .set(PLAYER_ROLE_GRANT.EXPIRES_AT, offset(g.expiresAt()))
                .set(PLAYER_ROLE_GRANT.REASON, g.reason())
                .set(PLAYER_ROLE_GRANT.ACTIVE, true)
                .execute();
    }

    @Override
    public boolean revokeRoleGrant(PlayerId player, RoleId role) {
        return dsl.update(PLAYER_ROLE_GRANT)
                .set(PLAYER_ROLE_GRANT.ACTIVE, false)
                .where(PLAYER_ROLE_GRANT.UUID.eq(player.value())
                        .and(PLAYER_ROLE_GRANT.ROLE_ID.eq(role.value()))
                        .and(PLAYER_ROLE_GRANT.ACTIVE.isTrue()))
                .execute() > 0;
    }

    @Override
    public void upsertPermissionGrant(PermissionGrant g) {
        dsl.insertInto(PLAYER_PERMISSION_GRANT)
                .set(PLAYER_PERMISSION_GRANT.UUID, g.player().value())
                .set(PLAYER_PERMISSION_GRANT.PERMISSION, g.permission())
                .set(PLAYER_PERMISSION_GRANT.ISSUED_BY, g.issuedBy())
                .set(PLAYER_PERMISSION_GRANT.ISSUED_AT, offset(g.issuedAt()))
                .set(PLAYER_PERMISSION_GRANT.EXPIRES_AT, offset(g.expiresAt()))
                .set(PLAYER_PERMISSION_GRANT.REASON, g.reason())
                .set(PLAYER_PERMISSION_GRANT.ACTIVE, true)
                .onConflict(PLAYER_PERMISSION_GRANT.UUID, PLAYER_PERMISSION_GRANT.PERMISSION)
                .doUpdate()
                .set(PLAYER_PERMISSION_GRANT.ISSUED_BY, g.issuedBy())
                .set(PLAYER_PERMISSION_GRANT.ISSUED_AT, offset(g.issuedAt()))
                .set(PLAYER_PERMISSION_GRANT.EXPIRES_AT, offset(g.expiresAt()))
                .set(PLAYER_PERMISSION_GRANT.REASON, g.reason())
                .set(PLAYER_PERMISSION_GRANT.ACTIVE, true)
                .execute();
    }

    @Override
    public boolean revokePermissionGrant(PlayerId player, String permission) {
        return dsl.update(PLAYER_PERMISSION_GRANT)
                .set(PLAYER_PERMISSION_GRANT.ACTIVE, false)
                .where(PLAYER_PERMISSION_GRANT.UUID.eq(player.value())
                        .and(PLAYER_PERMISSION_GRANT.PERMISSION.eq(permission))
                        .and(PLAYER_PERMISSION_GRANT.ACTIVE.isTrue()))
                .execute() > 0;
    }

    @Override
    public List<RoleGrant> activeRoleGrants(PlayerId player, Instant now) {
        return dsl.selectFrom(PLAYER_ROLE_GRANT)
                .where(PLAYER_ROLE_GRANT.UUID.eq(player.value())
                        .and(PLAYER_ROLE_GRANT.ACTIVE.isTrue())
                        .and(notExpired(now)))
                .fetch(r -> new RoleGrant(
                        PlayerId.of(r.get(PLAYER_ROLE_GRANT.UUID)),
                        RoleId.of(r.get(PLAYER_ROLE_GRANT.ROLE_ID)),
                        r.get(PLAYER_ROLE_GRANT.ISSUED_BY),
                        r.get(PLAYER_ROLE_GRANT.ISSUED_AT).toInstant(),
                        instant(r.get(PLAYER_ROLE_GRANT.EXPIRES_AT)),
                        r.get(PLAYER_ROLE_GRANT.REASON),
                        r.get(PLAYER_ROLE_GRANT.ACTIVE)));
    }

    @Override
    public List<PermissionGrant> activePermissionGrants(PlayerId player, Instant now) {
        return dsl.selectFrom(PLAYER_PERMISSION_GRANT)
                .where(PLAYER_PERMISSION_GRANT.UUID.eq(player.value())
                        .and(PLAYER_PERMISSION_GRANT.ACTIVE.isTrue())
                        .and(notExpiredPerm(now)))
                .fetch(r -> new PermissionGrant(
                        PlayerId.of(r.get(PLAYER_PERMISSION_GRANT.UUID)),
                        r.get(PLAYER_PERMISSION_GRANT.PERMISSION),
                        r.get(PLAYER_PERMISSION_GRANT.ISSUED_BY),
                        r.get(PLAYER_PERMISSION_GRANT.ISSUED_AT).toInstant(),
                        instant(r.get(PLAYER_PERMISSION_GRANT.EXPIRES_AT)),
                        r.get(PLAYER_PERMISSION_GRANT.REASON),
                        r.get(PLAYER_PERMISSION_GRANT.ACTIVE)));
    }

    @Override
    public List<PlayerId> activeHoldersOf(RoleId role, Instant now) {
        return dsl.select(PLAYER_ROLE_GRANT.UUID)
                .from(PLAYER_ROLE_GRANT)
                .where(PLAYER_ROLE_GRANT.ROLE_ID.eq(role.value())
                        .and(PLAYER_ROLE_GRANT.ACTIVE.isTrue())
                        .and(notExpired(now)))
                .fetch(r -> PlayerId.of(r.get(PLAYER_ROLE_GRANT.UUID)));
    }

    @Override
    public List<ExpiredGrant> findExpired(Instant now) {
        OffsetDateTime cutoff = offset(now);
        List<ExpiredGrant> out = new ArrayList<>();
        dsl.select(PLAYER_ROLE_GRANT.UUID, PLAYER_ROLE_GRANT.ROLE_ID)
                .from(PLAYER_ROLE_GRANT)
                .where(PLAYER_ROLE_GRANT.ACTIVE.isTrue()
                        .and(PLAYER_ROLE_GRANT.EXPIRES_AT.isNotNull())
                        .and(PLAYER_ROLE_GRANT.EXPIRES_AT.le(cutoff)))
                .forEach(r -> out.add(ExpiredGrant.role(
                        PlayerId.of(r.get(PLAYER_ROLE_GRANT.UUID)),
                        RoleId.of(r.get(PLAYER_ROLE_GRANT.ROLE_ID)))));
        dsl.select(PLAYER_PERMISSION_GRANT.UUID, PLAYER_PERMISSION_GRANT.PERMISSION)
                .from(PLAYER_PERMISSION_GRANT)
                .where(PLAYER_PERMISSION_GRANT.ACTIVE.isTrue()
                        .and(PLAYER_PERMISSION_GRANT.EXPIRES_AT.isNotNull())
                        .and(PLAYER_PERMISSION_GRANT.EXPIRES_AT.le(cutoff)))
                .forEach(r -> out.add(ExpiredGrant.permission(
                        PlayerId.of(r.get(PLAYER_PERMISSION_GRANT.UUID)),
                        r.get(PLAYER_PERMISSION_GRANT.PERMISSION))));
        return out;
    }

    @Override
    public void deactivate(ExpiredGrant g) {
        switch (g.type()) {
            case ROLE -> dsl.update(PLAYER_ROLE_GRANT)
                    .set(PLAYER_ROLE_GRANT.ACTIVE, false)
                    .where(PLAYER_ROLE_GRANT.UUID.eq(g.player().value())
                            .and(PLAYER_ROLE_GRANT.ROLE_ID.eq(g.role().value())))
                    .execute();
            case PERMISSION -> dsl.update(PLAYER_PERMISSION_GRANT)
                    .set(PLAYER_PERMISSION_GRANT.ACTIVE, false)
                    .where(PLAYER_PERMISSION_GRANT.UUID.eq(g.player().value())
                            .and(PLAYER_PERMISSION_GRANT.PERMISSION.eq(g.permission())))
                    .execute();
        }
    }

    private static Condition notExpired(Instant now) {
        return PLAYER_ROLE_GRANT.EXPIRES_AT.isNull().or(PLAYER_ROLE_GRANT.EXPIRES_AT.gt(offset(now)));
    }

    private static Condition notExpiredPerm(Instant now) {
        return PLAYER_PERMISSION_GRANT.EXPIRES_AT.isNull()
                .or(PLAYER_PERMISSION_GRANT.EXPIRES_AT.gt(offset(now)));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
