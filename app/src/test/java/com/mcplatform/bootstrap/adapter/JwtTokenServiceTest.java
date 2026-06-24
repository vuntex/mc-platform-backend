package com.mcplatform.bootstrap.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.webauth.port.AccessTokenInvalidException;
import com.mcplatform.domain.player.PlayerId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final String SECRET = "0123456789012345678901234567890123456789"; // 40 bytes
    private static final String OTHER_SECRET = "abcdefghijabcdefghijabcdefghijabcdefghij";

    private final JwtTokenService service = new JwtTokenService(SECRET);
    private final PlayerId player = PlayerId.of(UUID.randomUUID());

    @Test
    void issueThenVerifyRoundTripsTheSubject() {
        String token = service.issue(player, Duration.ofMinutes(15), Instant.now());
        assertThat(service.verify(token)).isEqualTo(player);
    }

    @Test
    void expiredTokenIsRejected() {
        // issued an hour ago with a 15-minute TTL → already expired against the real clock
        String token = service.issue(player, Duration.ofMinutes(15), Instant.now().minus(Duration.ofHours(1)));
        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(AccessTokenInvalidException.class);
    }

    @Test
    void tamperedOrForeignSignatureIsRejected() {
        String token = service.issue(player, Duration.ofMinutes(15), Instant.now());
        JwtTokenService otherKey = new JwtTokenService(OTHER_SECRET);
        assertThatThrownBy(() -> otherKey.verify(token)).isInstanceOf(AccessTokenInvalidException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> service.verify("not-a-jwt")).isInstanceOf(AccessTokenInvalidException.class);
    }

    @Test
    void secretUnderMinimumLengthFailsFast() {
        assertThatThrownBy(() -> new JwtTokenService("too-short"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokenService(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
