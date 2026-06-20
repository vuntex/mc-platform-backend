package com.mcplatform.protocol.core;

/**
 * Encodes/decodes a single feature payload to/from its wire string. The payload wire is opaque to
 * the {@link MessageEnvelope} that carries it (it may contain the {@code |} delimiter). Each codec
 * declares the {@code messageType} it owns, which is how {@link MessageProtocol} routes an incoming
 * envelope back to the right codec. Dependency-free: implementations use only the JDK.
 *
 * @param <T> the payload type this codec encodes/decodes
 */
public interface MessageCodec<T> {

    /** Stable identifier of the feature/event this codec handles, e.g. {@code "economy.balance-changed"}. */
    String messageType();

    /** Encodes the payload to its wire form (no envelope framing). */
    String encodePayload(T value);

    /** Decodes a payload wire previously produced by {@link #encodePayload}. */
    T decodePayload(String payload);
}
