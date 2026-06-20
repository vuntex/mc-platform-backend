package com.mcplatform.domain.punishment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PunishmentPolicyTest {

    private final PlayerId player = PlayerId.of(UUID.randomUUID());
    private final PlayerId staff = PlayerId.of(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-06-20T12:00:00Z");

    private Punishment active(PunishmentType type) {
        Instant expires = type.isTimeBound() ? now.plusSeconds(3600) : null;
        return new Punishment(PunishmentId.random(), player, type, "r", staff, now.minusSeconds(10),
                expires, null, null, 1);
    }

    private PendingPunishmentEvent issue(List<Punishment> activeSet, PunishmentType type) {
        Instant expires = type.isTimeBound() ? now.plusSeconds(3600) : null;
        return PunishmentPolicy.issue(activeSet, PunishmentId.random(), player, type, "reason", staff,
                now, expires, PunishmentTxId.random(), "WEB");
    }

    @Test
    void warnsAccumulate() {
        PendingPunishmentEvent e = issue(List.of(active(PunishmentType.WARN)), PunishmentType.WARN);
        assertThat(e.type()).isEqualTo(PunishmentType.WARN);
    }

    @Test
    void chatbanAndBanCoexist() {
        // A chatban active; issuing a (different-category) tempban is allowed.
        PendingPunishmentEvent e = issue(List.of(active(PunishmentType.CHATBAN)), PunishmentType.TEMPBAN);
        assertThat(e.type()).isEqualTo(PunishmentType.TEMPBAN);

        // ...and a warn coexists with both categories too.
        assertThat(issue(List.of(active(PunishmentType.CHATBAN), active(PunishmentType.TEMPBAN)),
                PunishmentType.WARN).type()).isEqualTo(PunishmentType.WARN);
    }

    @Test
    void secondActiveBanIsRejected() {
        assertThatThrownBy(() -> issue(List.of(active(PunishmentType.TEMPBAN)), PunishmentType.PERMABAN))
                .isInstanceOf(PunishmentConflictException.class);
        assertThatThrownBy(() -> issue(List.of(active(PunishmentType.PERMABAN)), PunishmentType.TEMPBAN))
                .isInstanceOf(PunishmentConflictException.class);
    }

    @Test
    void secondActiveChatbanIsRejected() {
        assertThatThrownBy(() -> issue(List.of(active(PunishmentType.CHATBAN)), PunishmentType.CHATBAN))
                .isInstanceOf(PunishmentConflictException.class);
    }

    @Test
    void expiredBanDoesNotBlockNewBan() {
        Punishment expired = new Punishment(PunishmentId.random(), player, PunishmentType.TEMPBAN, "r",
                staff, now.minusSeconds(7200), now.minusSeconds(1), null, null, 1);
        PendingPunishmentEvent e = issue(List.of(expired), PunishmentType.TEMPBAN);
        assertThat(e.type()).isEqualTo(PunishmentType.TEMPBAN);
    }

    @Test
    void revokedBanDoesNotBlockNewBan() {
        Punishment revoked = new Punishment(PunishmentId.random(), player, PunishmentType.PERMABAN, "r",
                staff, now.minusSeconds(7200), null, staff, now.minusSeconds(5), 2);
        PendingPunishmentEvent e = issue(List.of(revoked), PunishmentType.TEMPBAN);
        assertThat(e.type()).isEqualTo(PunishmentType.TEMPBAN);
    }
}
