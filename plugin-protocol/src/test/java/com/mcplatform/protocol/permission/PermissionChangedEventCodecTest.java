package com.mcplatform.protocol.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PermissionChangedEventCodecTest {

    private final PermissionChangedEventCodec codec = PermissionChangedEventCodec.INSTANCE;

    private PermissionChangedEvent sample() {
        return new PermissionChangedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "GRANT_EXPIRED", 1_750_000_000_000L);
    }

    @Test
    void payloadRoundTrips() {
        PermissionChangedEvent e = sample();
        assertThat(codec.decodePayload(codec.encodePayload(e))).isEqualTo(e);
    }

    @Test
    void goldenPayloadWire() {
        assertThat(codec.encodePayload(sample()))
                .isEqualTo("00000000-0000-0000-0000-000000000001|GRANT_EXPIRED|1750000000000");
    }

    @Test
    void routesThroughTheSharedProtocol() {
        MessageProtocol protocol = PlatformProtocol.create();
        String wire = protocol.encode(codec, sample());
        assertThat(wire).startsWith("v1|permission.changed|");
        assertThat(protocol.decode(wire, codec)).isEqualTo(sample());
    }

    @Test
    void rejectsWrongPartCount() {
        assertThatThrownBy(() -> codec.decodePayload("only|two"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
