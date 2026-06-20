package com.mcplatform.protocol.punishment;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PunishmentChangedEventCodecTest {

    private final PunishmentChangedEventCodec codec = PunishmentChangedEventCodec.INSTANCE;

    private PunishmentChangedEvent sample(String reason, long expiresAt) {
        return new PunishmentChangedEvent(UUID.randomUUID(), UUID.randomUUID(), "TEMPBAN", "ISSUED",
                reason, UUID.randomUUID(), expiresAt, 42L, 1_700_000_000_000L);
    }

    @Test
    void roundTripsThroughPayload() {
        PunishmentChangedEvent original = sample("Cheating", 1_700_100_000_000L);
        PunishmentChangedEvent decoded = codec.decodePayload(codec.encodePayload(original));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void reasonWithPipeAndSpacesSurvives() {
        PunishmentChangedEvent original = sample("spam | abuse & 100% bad", 0);
        PunishmentChangedEvent decoded = codec.decodePayload(codec.encodePayload(original));
        assertThat(decoded.reason()).isEqualTo("spam | abuse & 100% bad");
        assertThat(decoded.expiresAtEpochMilli()).isZero();
    }

    @Test
    void routesThroughTheSharedProtocolEnvelope() {
        MessageProtocol protocol = PlatformProtocol.create();
        PunishmentChangedEvent original = sample("Cheating", 1_700_100_000_000L);

        String wire = protocol.encode(codec, original);
        assertThat(wire).startsWith("v1|" + PunishmentChangedEventCodec.MESSAGE_TYPE + "|");

        PunishmentChangedEvent decoded = protocol.decode(wire, codec);
        assertThat(decoded).isEqualTo(original);
    }
}
