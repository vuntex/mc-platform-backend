package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.ROLE;
import static com.mcplatform.persistence.jooq.Tables.ROLE_PERMISSION;

import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** jOOQ adapter over the state-stored {@code role} / {@code role_permission} tables. No Spring. */
public final class JooqRoleRepository implements RoleRepository {

    private final DSLContext dsl;

    public JooqRoleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Role create(Role role, UUID createdBy) {
        long id = dsl.insertInto(ROLE)
                .set(ROLE.NAME, role.name())
                .set(ROLE.DISPLAY_NAME, role.displayName())
                .set(ROLE.COLOR, role.color())
                .set(ROLE.PREFIX, role.prefix())
                .set(ROLE.SUFFIX, role.suffix())
                .set(ROLE.TAB_LIST_COLOR, role.tabListColor())
                .set(ROLE.TAB_LIST_ICON, role.tabListIcon())
                .set(ROLE.DISPLAY_ICON, role.displayIcon())
                .set(ROLE.WEIGHT, role.weight())
                .set(ROLE.TEAM_RANK, role.teamRank())
                .set(ROLE.ACTIVE, role.active())
                .set(ROLE.IS_DEFAULT, role.isDefault())
                .set(ROLE.CREATED_BY, createdBy)
                .returning(ROLE.ID)
                .fetchOne()
                .get(ROLE.ID);
        return withId(role, id);
    }

    @Override
    public Role update(Role role) {
        dsl.update(ROLE)
                .set(ROLE.NAME, role.name())
                .set(ROLE.DISPLAY_NAME, role.displayName())
                .set(ROLE.COLOR, role.color())
                .set(ROLE.PREFIX, role.prefix())
                .set(ROLE.SUFFIX, role.suffix())
                .set(ROLE.TAB_LIST_COLOR, role.tabListColor())
                .set(ROLE.TAB_LIST_ICON, role.tabListIcon())
                .set(ROLE.DISPLAY_ICON, role.displayIcon())
                .set(ROLE.WEIGHT, role.weight())
                .set(ROLE.TEAM_RANK, role.teamRank())
                .set(ROLE.ACTIVE, role.active())
                .where(ROLE.ID.eq(role.id().value()))
                .execute();
        return role;
    }

    @Override
    public void delete(RoleId id) {
        dsl.deleteFrom(ROLE).where(ROLE.ID.eq(id.value())).execute();
    }

    @Override
    public Optional<Role> find(RoleId id) {
        Record r = dsl.selectFrom(ROLE).where(ROLE.ID.eq(id.value())).fetchOne();
        return r == null ? Optional.empty() : Optional.of(toRole(r));
    }

    @Override
    public Optional<Role> findByNameIgnoreCase(String name) {
        Record r = dsl.selectFrom(ROLE).where(ROLE.NAME.equalIgnoreCase(name)).fetchOne();
        return r == null ? Optional.empty() : Optional.of(toRole(r));
    }

    @Override
    public List<Role> findAll() {
        return dsl.selectFrom(ROLE).orderBy(ROLE.WEIGHT.desc(), ROLE.ID.asc()).fetch(JooqRoleRepository::toRole);
    }

    @Override
    public Role findDefault() {
        Record r = dsl.selectFrom(ROLE).where(ROLE.IS_DEFAULT.isTrue()).fetchOne();
        if (r == null) {
            throw new IllegalStateException("default role missing (should be seeded by V9)");
        }
        return toRole(r);
    }

    @Override
    public List<Role> findActiveByIds(Collection<RoleId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> raw = ids.stream().map(RoleId::value).toList();
        return dsl.selectFrom(ROLE)
                .where(ROLE.ID.in(raw).and(ROLE.ACTIVE.isTrue()))
                .fetch(JooqRoleRepository::toRole);
    }

    @Override
    public List<String> permissionsOf(RoleId id) {
        return dsl.select(ROLE_PERMISSION.PERMISSION)
                .from(ROLE_PERMISSION)
                .where(ROLE_PERMISSION.ROLE_ID.eq(id.value()))
                .fetch(ROLE_PERMISSION.PERMISSION);
    }

    @Override
    public void addPermission(RoleId id, String permission, UUID addedBy) {
        dsl.insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ROLE_ID, id.value())
                .set(ROLE_PERMISSION.PERMISSION, permission)
                .set(ROLE_PERMISSION.ADDED_BY, addedBy)
                .onConflictDoNothing()
                .execute();
    }

    @Override
    public void removePermission(RoleId id, String permission) {
        dsl.deleteFrom(ROLE_PERMISSION)
                .where(ROLE_PERMISSION.ROLE_ID.eq(id.value()).and(ROLE_PERMISSION.PERMISSION.eq(permission)))
                .execute();
    }

    private static Role withId(Role r, long id) {
        return new Role(RoleId.of(id), r.name(), r.displayName(), r.color(), r.prefix(), r.suffix(),
                r.tabListColor(), r.tabListIcon(), r.displayIcon(), r.weight(), r.teamRank(), r.active(),
                r.isDefault());
    }

    private static Role toRole(Record r) {
        return new Role(
                RoleId.of(r.get(ROLE.ID)),
                r.get(ROLE.NAME),
                r.get(ROLE.DISPLAY_NAME),
                r.get(ROLE.COLOR),
                r.get(ROLE.PREFIX),
                r.get(ROLE.SUFFIX),
                r.get(ROLE.TAB_LIST_COLOR),
                r.get(ROLE.TAB_LIST_ICON),
                r.get(ROLE.DISPLAY_ICON),
                r.get(ROLE.WEIGHT),
                r.get(ROLE.TEAM_RANK),
                r.get(ROLE.ACTIVE),
                r.get(ROLE.IS_DEFAULT));
    }
}
