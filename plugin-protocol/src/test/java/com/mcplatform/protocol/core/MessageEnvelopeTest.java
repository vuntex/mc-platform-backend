package com.mcplatform.protocol.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageEnvelopeTest {

    @Test
    void roundTripsAndPreservesPipesInPayload() {
        MessageEnvelope env = new MessageEnvelope(1, "economy.balance-changed", "a|b|c");
        String wire = env.toWire();
        assertThat(wire).isEqualTo("v1|economy.balance-changed|a|b|c");

        MessageEnvelope parsed = MessageEnvelope.parse(wire);
        assertThat(parsed).isEqualTo(env);
        assertThat(parsed.payload()).as("payload keeps its own delimiters").isEqualTo("a|b|c");
    }

    @Test
    void allowsEmptyPayload() {
        MessageEnvelope env = new MessageEnvelope(1, "x.y", "");
        assertThat(MessageEnvelope.parse(env.toWire())).isEqualTo(env);
    }

    @Test
    void rejectsMessageTypeWithDelimiterOrBlank() {
        assertThatThrownBy(() -> new MessageEnvelope(1, "bad|type", "p"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEnvelope(1, "  ", "p"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedWire() {
        assertThatThrownBy(() -> MessageEnvelope.parse("v1|onlytwo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageEnvelope.parse("xx|type|p"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageEnvelope.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
