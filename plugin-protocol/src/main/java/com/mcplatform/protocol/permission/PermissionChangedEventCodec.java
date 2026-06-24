package com.mcplatform.protocol.permission;

import com.mcplatform.protocol.core.MessageCodec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@link MessageCodec} for {@link PermissionChangedEvent}. The payload travels inside a
 * {@link com.mcplatform.protocol.core.MessageEnvelope} (version + routing are the envelope's job).
 * Dependency-free so the plugin needs no JSON library to read events.
 *
 * <p>Payload — pipe-delimited, exactly 3 parts:
 * <pre>playerUuid|changeType|timestampEpochMilli</pre>
 * {@code changeType} is URL-encoded (UTF-8) so it can never contain the {@code |} delimiter.
 */
public final class PermissionChangedEventCodec implements MessageCodec<PermissionChangedEvent> {

    /** Routing key for this payload within the envelope. */
    public static final String MESSAGE_TYPE = "permission.changed";

    /** Shared stateless instance — register this in the protocol. */
    public static final PermissionChangedEventCodec INSTANCE = new PermissionChangedEventCodec();

    private static final String SEP = "|";
    private static final int PARTS = 3;

    public PermissionChangedEventCodec() {}

    @Override
    public String messageType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String encodePayload(PermissionChangedEvent e) {
        return String.join(SEP,
                e.playerUuid().toString(),
                enc(e.changeType()),
                Long.toString(e.timestampEpochMilli()));
    }

    @Override
    public PermissionChangedEvent decodePayload(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String[] p = payload.split("\\" + SEP, -1);
        if (p.length != PARTS) {
            throw new IllegalArgumentException("expected " + PARTS + " parts, got " + p.length + ": " + payload);
        }
        try {
            return new PermissionChangedEvent(UUID.fromString(p[0]), dec(p[1]), Long.parseLong(p[2]));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed permission payload: " + payload, ex);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
