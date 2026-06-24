package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;

/**
 * A grant that has passed its expiry while still active — surfaced by {@link PlayerGrantRepository#findExpired}
 * so the sweep can deactivate it, audit an EXPIRE and publish a live-update (FR-020). Exactly one of
 * {@code role}/{@code permission} is set, per {@code type}.
 */
public record ExpiredGrant(PlayerId player, GrantType type, RoleId role, String permission) {

    public static ExpiredGrant role(PlayerId player, RoleId role) {
        return new ExpiredGrant(player, GrantType.ROLE, role, null);
    }

    public static ExpiredGrant permission(PlayerId player, String permission) {
        return new ExpiredGrant(player, GrantType.PERMISSION, null, permission);
    }
}
