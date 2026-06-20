package com.mcplatform.protocol.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageProtocolTest {

    // Two tiny fake feature codecs prove routing is generic, not economy-specific.
    private static final MessageCodec<String> ALPHA = new MessageCodec<>() {
        public String messageType() { return "test.alpha"; }
        public String encodePayload(String v) { return v; }
        public String decodePayload(String p) { return p; }
    };
    private static final MessageCodec<Integer> BETA = new MessageCodec<>() {
        public String messageType() { return "test.beta"; }
        public String encodePayload(Integer v) { return Integer.toString(v); }
        public Integer decodePayload(String p) { return Integer.parseInt(p); }
    };

    private final MessageProtocol protocol = new MessageProtocol(ALPHA, BETA);

    @Test
    void routesEachTypeToItsCodec() {
        String aWire = protocol.encode(ALPHA, "hello|world"); // payload with a pipe
        String bWire = protocol.encode(BETA, 42);

        assertThat(protocol.decode(aWire)).isEqualTo("hello|world");
        assertThat(protocol.decode(bWire)).isEqualTo(42);
        assertThat(protocol.peek(aWire).messageType()).isEqualTo("test.alpha");
        assertThat(protocol.decode(bWire, BETA)).isEqualTo(42);
    }

    @Test
    void typedDecodeRejectsMismatchedType() {
        String aWire = protocol.encode(ALPHA, "x");
        assertThatThrownBy(() -> protocol.decode(aWire, BETA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownTypeAndUnsupportedVersion() {
        assertThatThrownBy(() -> protocol.decode("v1|test.unknown|p"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protocol.decode("v2|test.alpha|p"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateMessageType() {
        assertThatThrownBy(() -> new MessageProtocol(ALPHA, ALPHA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
