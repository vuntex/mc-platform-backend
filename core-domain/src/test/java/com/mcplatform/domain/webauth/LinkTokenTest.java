package com.mcplatform.domain.webauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkTokenTest {

    private final Instant expiry = Instant.parse("2026-06-24T12:10:00Z");
    private final LinkToken token = new LinkToken(
            PlayerId.of(UUID.randomUUID()), TokenPurpose.LINK, expiry, expiry.minusSeconds(600));

    @Test
    void notExpiredBeforeExpiry() {
        assertThat(token.isExpired(expiry.minusSeconds(1))).isFalse();
    }

    @Test
    void expiredAtExpiryInstant() {
        assertThat(token.isExpired(expiry)).isTrue();
    }

    @Test
    void expiredAfterExpiry() {
        assertThat(token.isExpired(expiry.plusSeconds(1))).isTrue();
    }
}
