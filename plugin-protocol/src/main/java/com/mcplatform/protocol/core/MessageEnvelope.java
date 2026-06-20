package com.mcplatform.protocol.core;

import java.util.Objects;

/**
 * Transport-neutral framing for a single pub/sub message: a protocol version, a {@code messageType}
 * that identifies the feature/event (e.g. {@code "economy.balance-changed"}), and an opaque,
 * already-encoded {@code payload}. Dependency-free (JDK only); the wire is pipe-delimited and
 * URL-encoding-friendly exactly like the original economy codec.
 *
 * <p>Wire: <pre>v&lt;version&gt;|&lt;messageType&gt;|&lt;payload&gt;</pre> The payload may itself
 * contain {@code |} — parsing splits with a limit so only the first two delimiters frame the
 * envelope and the payload keeps its own structure. {@code messageType} therefore must not contain
 * {@code |}.
 */
public record MessageEnvelope(int protocolVersion, String messageType, String payload) {

    private static final String SEP = "|";

    public MessageEnvelope {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(payload, "payload");
        if (messageType.isBlank()) {
            throw new IllegalArgumentException("messageType must not be blank");
        }
        if (messageType.contains(SEP)) {
            throw new IllegalArgumentException("messageType must not contain '" + SEP + "': " + messageType);
        }
        if (protocolVersion < 1) {
            throw new IllegalArgumentException("protocolVersion must be >= 1: " + protocolVersion);
        }
    }

    /** Serializes this envelope to the pipe-delimited wire. */
    public String toWire() {
        return "v" + protocolVersion + SEP + messageType + SEP + payload;
    }

    /** Parses the envelope framing only; the payload is returned undecoded (its own delimiters intact). */
    public static MessageEnvelope parse(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("wire must not be null");
        }
        // limit 3: version | messageType | payload(rest, with its own pipes preserved)
        String[] p = wire.split("\\" + SEP, 3);
        if (p.length != 3) {
            throw new IllegalArgumentException("expected 'v<n>|messageType|payload', got: " + wire);
        }
        String versionToken = p[0];
        if (versionToken.length() < 2 || versionToken.charAt(0) != 'v') {
            throw new IllegalArgumentException("malformed version token: " + versionToken);
        }
        int version;
        try {
            version = Integer.parseInt(versionToken.substring(1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("malformed version token: " + versionToken, ex);
        }
        return new MessageEnvelope(version, p[1], p[2]);
    }
}
