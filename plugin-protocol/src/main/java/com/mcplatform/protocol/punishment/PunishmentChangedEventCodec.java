package com.mcplatform.protocol.punishment;

import com.mcplatform.protocol.core.MessageCodec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@link MessageCodec} for {@link PunishmentChangedEvent}. Produces the payload that travels inside a
 * {@link com.mcplatform.protocol.core.MessageEnvelope} — the protocol version and the routing
 * {@code messageType} are the envelope's job, so this payload is just the fields. Dependency-free so
 * the plugin needs no JSON library to read events.
 *
 * <p>Payload — pipe-delimited, exactly 9 parts:
 * <pre>punishmentId|playerUuid|type|action|reason|actor|expiresAtEpochMilli|version|timestampEpochMilli</pre>
 * The String fields (type, action, reason) are URL-encoded (UTF-8) so they can never contain the
 * {@code |} delimiter. {@code version} carries the global ordering for staleness checks.
 */
public final class PunishmentChangedEventCodec implements MessageCodec<PunishmentChangedEvent> {

    /** Routing key for this payload within the envelope. */
    public static final String MESSAGE_TYPE = "punishment.changed";

    /** Shared stateless instance — register this in the protocol. */
    public static final PunishmentChangedEventCodec INSTANCE = new PunishmentChangedEventCodec();

    private static final String SEP = "|";
    private static final int PARTS = 9;

    public PunishmentChangedEventCodec() {}

    @Override
    public String messageType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String encodePayload(PunishmentChangedEvent e) {
        return String.join(SEP,
                e.punishmentId().toString(),
                e.playerUuid().toString(),
                enc(e.type()),
                enc(e.action()),
                enc(e.reason()),
                e.actor().toString(),
                Long.toString(e.expiresAtEpochMilli()),
                Long.toString(e.version()),
                Long.toString(e.timestampEpochMilli()));
    }

    @Override
    public PunishmentChangedEvent decodePayload(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String[] p = payload.split("\\" + SEP, -1);
        if (p.length != PARTS) {
            throw new IllegalArgumentException("expected " + PARTS + " parts, got " + p.length + ": " + payload);
        }
        try {
            return new PunishmentChangedEvent(
                    UUID.fromString(p[0]),
                    UUID.fromString(p[1]),
                    dec(p[2]),
                    dec(p[3]),
                    dec(p[4]),
                    UUID.fromString(p[5]),
                    Long.parseLong(p[6]),
                    Long.parseLong(p[7]),
                    Long.parseLong(p[8]));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed punishment payload: " + payload, ex);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
