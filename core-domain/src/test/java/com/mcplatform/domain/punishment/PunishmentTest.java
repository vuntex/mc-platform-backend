package com.mcplatform.domain.punishment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PunishmentTest {

    private final PlayerId player = PlayerId.of(UUID.randomUUID());
    private final PlayerId staff = PlayerId.of(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-06-20T12:00:00Z");

    private Punishment punishment(PunishmentType type, Instant expiresAt, Instant revokedAt) {
        return new Punishment(PunishmentId.random(), player, type, "reason", staff,
                now.minusSeconds(60), expiresAt, revokedAt == null ? null : staff, revokedAt, 1);
    }

    @Test
    void permanentUnrevokedIsActive() {
        assertThat(punishment(PunishmentType.PERMABAN, null, null).isActive(now)).isTrue();
    }

    @Test
    void tempbanInTheFutureIsActive() {
        assertThat(punishment(PunishmentType.TEMPBAN, now.plusSeconds(60), null).isActive(now)).isTrue();
    }

    @Test
    void expiredTempbanIsInactive() {
        assertThat(punishment(PunishmentType.TEMPBAN, now.minusSeconds(1), null).isActive(now)).isFalse();
    }

    @Test
    void revokedIsInactiveEvenIfNotExpired() {
        assertThat(punishment(PunishmentType.TEMPBAN, now.plusSeconds(3600), now.minusSeconds(10)).isActive(now))
                .isFalse();
    }

    @Test
    void categoryFollowsType() {
        assertThat(PunishmentType.WARN.category()).isEqualTo(PunishmentCategory.NOTICE);
        assertThat(PunishmentType.CHATBAN.category()).isEqualTo(PunishmentCategory.CHAT);
        assertThat(PunishmentType.TEMPBAN.category()).isEqualTo(PunishmentCategory.ACCESS);
        assertThat(PunishmentType.PERMABAN.category()).isEqualTo(PunishmentCategory.ACCESS);
        assertThat(PunishmentCategory.NOTICE.isExclusive()).isFalse();
        assertThat(PunishmentCategory.CHAT.isExclusive()).isTrue();
        assertThat(PunishmentCategory.ACCESS.isExclusive()).isTrue();
    }
}
