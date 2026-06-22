package com.mcplatform.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GrantActivityTest {

    private static final PlayerId PLAYER = PlayerId.of(UUID.randomUUID());
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-23T12:00:00Z");

    private RoleGrant roleGrant(Instant expiresAt, boolean active) {
        return new RoleGrant(PLAYER, RoleId.of(1), ACTOR, NOW.minusSeconds(3600), expiresAt, null, active);
    }

    @Test
    void permanentGrantIsActive() {
        assertThat(roleGrant(null, true).isActive(NOW)).isTrue();
    }

    @Test
    void futureExpiryIsActive() {
        assertThat(roleGrant(NOW.plusSeconds(60), true).isActive(NOW)).isTrue();
    }

    @Test
    void expiredGrantDropsOut() {
        assertThat(roleGrant(NOW.minusSeconds(1), true).isActive(NOW)).isFalse();
    }

    @Test
    void exactlyAtExpiryIsInactive() {
        // FR-006: expires_at <= now is inactive.
        assertThat(roleGrant(NOW, true).isActive(NOW)).isFalse();
    }

    @Test
    void softRevokedIsInactiveEvenIfNotExpired() {
        assertThat(roleGrant(NOW.plusSeconds(60), false).isActive(NOW)).isFalse();
    }

    @Test
    void permissionGrantHonoursSameRule() {
        PermissionGrant g = new PermissionGrant(PLAYER, "kit.vip", ACTOR, NOW.minusSeconds(10),
                NOW.minusSeconds(1), null, true);
        assertThat(g.isActive(NOW)).isFalse();
    }
}
