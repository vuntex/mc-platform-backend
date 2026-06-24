package com.mcplatform.domain.webauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final PlayerId PLAYER = PlayerId.of(UUID.randomUUID());
    private static final Instant CREATED = Instant.parse("2026-06-24T12:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-07-24T12:00:00Z");

    private RefreshToken token(Instant rotatedAt) {
        return new RefreshToken("hash", PLAYER, CREATED, EXPIRES, rotatedAt, null);
    }

    @Test
    void activeWhenNotConsumedAndNotExpired() {
        RefreshToken t = token(null);
        Instant within = Instant.parse("2026-07-01T00:00:00Z");
        assertThat(t.isConsumed()).isFalse();
        assertThat(t.isExpired(within)).isFalse();
        assertThat(t.isActive(within)).isTrue();
    }

    @Test
    void expiredAtAndAfterExpiry() {
        RefreshToken t = token(null);
        assertThat(t.isExpired(EXPIRES)).isTrue();                          // boundary = expired
        assertThat(t.isExpired(EXPIRES.plusSeconds(1))).isTrue();
        assertThat(t.isActive(EXPIRES)).isFalse();
    }

    @Test
    void consumedTokenIsNeverActiveEvenBeforeExpiry() {
        RefreshToken t = token(Instant.parse("2026-07-01T00:00:00Z"));
        Instant within = Instant.parse("2026-07-02T00:00:00Z");
        assertThat(t.isConsumed()).isTrue();
        assertThat(t.isExpired(within)).isFalse();
        assertThat(t.isActive(within)).isFalse();
    }

    @Test
    void predecessorReflectsRotatedFrom() {
        assertThat(token(null).predecessor()).isEmpty();
        RefreshToken rotated = new RefreshToken("h2", PLAYER, CREATED, EXPIRES, null, "h1");
        assertThat(rotated.predecessor()).contains("h1");
    }
}
