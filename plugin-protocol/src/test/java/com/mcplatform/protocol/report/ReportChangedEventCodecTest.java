package com.mcplatform.protocol.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportChangedEventCodecTest {

    private final ReportChangedEventCodec codec = ReportChangedEventCodec.INSTANCE;

    private ReportChangedEvent sample() {
        return new ReportChangedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "CHEATING", "OPEN", "CREATED", 1_750_000_000_000L);
    }

    @Test
    void payloadRoundTrips() {
        ReportChangedEvent e = sample();
        assertThat(codec.decodePayload(codec.encodePayload(e))).isEqualTo(e);
    }

    @Test
    void goldenPayloadWire() {
        assertThat(codec.encodePayload(sample()))
                .isEqualTo("00000000-0000-0000-0000-000000000001|00000000-0000-0000-0000-000000000002|"
                        + "00000000-0000-0000-0000-000000000003|CHEATING|OPEN|CREATED|1750000000000");
    }

    @Test
    void routesThroughTheSharedProtocol() {
        MessageProtocol protocol = PlatformProtocol.create();
        String wire = protocol.encode(codec, sample());
        assertThat(wire).startsWith("v1|report.changed|");
        assertThat(protocol.decode(wire, codec)).isEqualTo(sample());
    }

    @Test
    void rejectsWrongPartCount() {
        assertThatThrownBy(() -> codec.decodePayload("only|three|parts"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
