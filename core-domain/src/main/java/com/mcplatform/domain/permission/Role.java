package com.mcplatform.domain.permission;

import java.util.Objects;

/**
 * Flat role master data (the old {@code rank}/{@code rank_data}/{@code rank_server_data} triple, merged
 * into one — no server scope, no inheritance). Display fields ({@code displayName}, {@code color},
 * {@code prefix}, {@code suffix}, {@code tabList*}), {@code weight} and {@code teamRank} affect ONLY the
 * presentation/display selection ({@link RankDisplay}, FR-010/FR-019), never permission resolution.
 *
 * <p>Pure data: {@code name} and {@code displayName} are required; the remaining display fields are
 * optional (nullable). {@code displayIcon} is an opaque, plugin-interpreted icon reference (see
 * {@link RoleDisplayIcon}). {@code isDefault} marks the single auto-created default role (FR-012);
 * {@code active=false} means the role contributes nothing to resolution or display (FR-007a).
 */
public record Role(
        RoleId id,
        String name,
        String displayName,
        String color,
        String prefix,
        String suffix,
        String tabListColor,
        String tabListIcon,
        String displayIcon,
        int weight,
        boolean teamRank,
        boolean active,
        boolean isDefault) {

    public Role {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new RoleValidationException("role name must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new RoleValidationException("role displayName must not be blank");
        }
        RoleDisplayIcon.validate(displayIcon);
    }
}
