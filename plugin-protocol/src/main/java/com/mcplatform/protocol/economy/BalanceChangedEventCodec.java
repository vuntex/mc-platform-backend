package com.mcplatform.protocol.economy;

import com.mcplatform.protocol.core.MessageCodec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@link MessageCodec} for {@link BalanceChangedEvent}. Produces the payload that travels inside a
 * {@link com.mcplatform.protocol.core.MessageEnvelope} — the protocol version and the routing
 * {@code messageType} are the envelope's job, so this payload is just the fields. Dependency-free so
 * the plugin needs no JSON library to read events.
 *
 * <p>Payload — pipe-delimited, exactly 10 parts:
 * <pre>playerUuid|currencyCode|eventType|amount|balance|version|transactionId|source|correlationId|timestampEpochMilli</pre>
 * The String fields (currencyCode, eventType, source) are URL-encoded (UTF-8) so they can never
 * contain the {@code |} delimiter; {@code correlationId} is empty when absent. {@code version}
 * carries the global ordering for staleness checks against out-of-order events.
 */
public final class BalanceChangedEventCodec implements MessageCodec<BalanceChangedEvent> {

    /** Routing key for this payload within the envelope. */
    public static final String MESSAGE_TYPE = "economy.balance-changed";

    /** Shared stateless instance — register this in the protocol. */
    public static final BalanceChangedEventCodec INSTANCE = new BalanceChangedEventCodec();

    private static final String SEP = "|";
    private static final int PARTS = 10;

    public BalanceChangedEventCodec() {}

    @Override
    public String messageType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String encodePayload(BalanceChangedEvent e) {
        return String.join(SEP,
                e.playerUuid().toString(),
                enc(e.currencyCode()),
                enc(e.eventType()),
                Long.toString(e.amount()),
                Long.toString(e.balance()),
                Long.toString(e.version()),
                e.transactionId().toString(),
                enc(e.source()),
                e.correlationId() == null ? "" : e.correlationId().toString(),
                Long.toString(e.timestampEpochMilli()));
    }

    @Override
    public BalanceChangedEvent decodePayload(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String[] p = payload.split("\\" + SEP, -1);
        if (p.length != PARTS) {
            throw new IllegalArgumentException("expected " + PARTS + " parts, got " + p.length + ": " + payload);
        }
        try {
            return new BalanceChangedEvent(
                    UUID.fromString(p[0]),
                    dec(p[1]),
                    dec(p[2]),
                    Long.parseLong(p[3]),
                    Long.parseLong(p[4]),
                    Long.parseLong(p[5]),
                    UUID.fromString(p[6]),
                    dec(p[7]),
                    p[8].isEmpty() ? null : UUID.fromString(p[8]),
                    Long.parseLong(p[9]));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed balance payload: " + payload, ex);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
