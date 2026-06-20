package com.mcplatform.protocol.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.protocol.PlatformProtocol;
import com.mcplatform.protocol.core.MessageEnvelope;
import com.mcplatform.protocol.core.MessageProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalanceChangedEventCodecTest {

    private final MessageProtocol protocol = PlatformProtocol.create();

    @Test
    void roundTripsThroughEnvelopeWithoutCorrelationId() {
        BalanceChangedEvent e = new BalanceChangedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "COINS", "DEBITED", 50L, 950L, 42L,
                UUID.fromString("00000000-0000-0000-0000-0000000000ff"),
                "PLUGIN:shop", null, 1_700_000_000_000L);

        String wire = protocol.encode(BalanceChangedEventCodec.INSTANCE, e);
        // both the typed decode and the routed (type-agnostic) decode reconstruct the event
        assertThat(protocol.decode(wire, BalanceChangedEventCodec.INSTANCE)).isEqualTo(e);
        assertThat(protocol.decode(wire)).isEqualTo(e);
    }

    @Test
    void roundTripsTransferLegWithCorrelationId() {
        BalanceChangedEvent e = new BalanceChangedEvent(
                UUID.randomUUID(), "COINS", "TRANSFER_OUT", 30L, 70L, 7L,
                UUID.randomUUID(), "WEB:transfer", UUID.randomUUID(), 123L);

        String wire = protocol.encode(BalanceChangedEventCodec.INSTANCE, e);
        assertThat(protocol.decode(wire, BalanceChangedEventCodec.INSTANCE)).isEqualTo(e);
    }

    @Test
    void survivesDelimiterAndUnicodeInStringFields() {
        BalanceChangedEvent e = new BalanceChangedEvent(
                UUID.randomUUID(), "GE|MS", "SET", 0L, 1L, 1L,
                UUID.randomUUID(), "SYSTEM:reason with | pipe & spaces ✨", null, 0L);

        String wire = protocol.encode(BalanceChangedEventCodec.INSTANCE, e);

        MessageEnvelope env = protocol.peek(wire);
        assertThat(env.messageType()).isEqualTo("economy.balance-changed");
        // URL-encoded string fields never leak the '|' delimiter: payload still has exactly 10 parts
        assertThat(env.payload().split("\\|", -1))
                .as("delimiter never leaks into payload fields").hasSize(10);
        assertThat(protocol.decode(wire, BalanceChangedEventCodec.INSTANCE)).isEqualTo(e);
    }

    @Test
    void goldenWireIsStableAndExplicit() {
        BalanceChangedEvent e = new BalanceChangedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "COINS", "DEBITED", 50L, 950L, 42L,
                UUID.fromString("00000000-0000-0000-0000-0000000000ff"),
                "PLUGIN:shop", null, 1_700_000_000_000L);

        // Wire changed BY DESIGN vs the old flat "v1|<11 fields>" codec: routing needs the messageType
        // on the wire, so the envelope now carries (version | messageType | <versionless 10-field
        // payload>). protocolVersion stays v1 (no external consumer yet). Pinned here on purpose so any
        // future change is a conscious, reviewed edit.
        String expected = "v1|economy.balance-changed|"
                + "00000000-0000-0000-0000-000000000001|COINS|DEBITED|50|950|42|"
                + "00000000-0000-0000-0000-0000000000ff|PLUGIN%3Ashop||1700000000000";
        assertThat(protocol.encode(BalanceChangedEventCodec.INSTANCE, e)).isEqualTo(expected);
        // and it round-trips back from exactly that pinned wire
        assertThat(protocol.decode(expected, BalanceChangedEventCodec.INSTANCE)).isEqualTo(e);
    }

    @Test
    void rejectsUnknownTypeArityAndVersion() {
        assertThatThrownBy(() -> protocol.decode("v1|cosmetics.unknown|x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.decode("v2|economy.balance-changed|x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.decode("v1|economy.balance-changed|too|few"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.decode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
