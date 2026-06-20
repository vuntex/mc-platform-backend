package com.mcplatform.protocol.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Routing + versioning for all feature messages, solved once. Holds the {@link MessageCodec}s keyed
 * by {@link MessageCodec#messageType()}; on encode it wraps a payload in a {@link MessageEnvelope}
 * at the current {@link #PROTOCOL_VERSION}, on decode it validates the version and dispatches the
 * incoming wire back to the codec that owns its {@code messageType}. A new feature plugs in by being
 * registered here — no envelope/version logic is duplicated per feature. Dependency-free.
 */
public final class MessageProtocol {

    /** Current envelope protocol version; written on encode, required on decode. */
    public static final int PROTOCOL_VERSION = 1;

    private final Map<String, MessageCodec<?>> codecsByType = new LinkedHashMap<>();

    public MessageProtocol(MessageCodec<?>... codecs) {
        for (MessageCodec<?> codec : codecs) {
            Objects.requireNonNull(codec, "codec");
            MessageCodec<?> prev = codecsByType.put(codec.messageType(), codec);
            if (prev != null) {
                throw new IllegalArgumentException("duplicate messageType: " + codec.messageType());
            }
        }
    }

    /** Frames {@code value} (encoded by {@code codec}) into an envelope wire at the current version. */
    public <T> String encode(MessageCodec<T> codec, T value) {
        String payload = codec.encodePayload(value);
        return new MessageEnvelope(PROTOCOL_VERSION, codec.messageType(), payload).toWire();
    }

    /** Parses framing only — lets a dispatcher read {@code messageType} without decoding the payload. */
    public MessageEnvelope peek(String wire) {
        return requireSupported(MessageEnvelope.parse(wire));
    }

    /** Routes an incoming wire to the registered codec for its {@code messageType} and decodes it. */
    public Object decode(String wire) {
        MessageEnvelope env = requireSupported(MessageEnvelope.parse(wire));
        MessageCodec<?> codec = codecsByType.get(env.messageType());
        if (codec == null) {
            throw new IllegalArgumentException("no codec registered for messageType: " + env.messageType());
        }
        return codec.decodePayload(env.payload());
    }

    /** Decodes a wire expected to carry {@code expected}'s message type, returning the typed payload. */
    public <T> T decode(String wire, MessageCodec<T> expected) {
        MessageEnvelope env = requireSupported(MessageEnvelope.parse(wire));
        if (!expected.messageType().equals(env.messageType())) {
            throw new IllegalArgumentException(
                    "expected messageType " + expected.messageType() + " but got " + env.messageType());
        }
        return expected.decodePayload(env.payload());
    }

    private MessageEnvelope requireSupported(MessageEnvelope env) {
        if (env.protocolVersion() != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unsupported protocol version: v" + env.protocolVersion());
        }
        return env;
    }
}
