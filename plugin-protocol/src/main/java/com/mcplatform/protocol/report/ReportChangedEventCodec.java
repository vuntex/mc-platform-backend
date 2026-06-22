package com.mcplatform.protocol.report;

import com.mcplatform.protocol.core.MessageCodec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@link MessageCodec} for {@link ReportChangedEvent}. The payload travels inside a
 * {@link com.mcplatform.protocol.core.MessageEnvelope} (version + routing are the envelope's job).
 * Dependency-free so the plugin needs no JSON library to read events.
 *
 * <p>Payload — pipe-delimited, exactly 7 parts:
 * <pre>reportId|reporter|target|category|status|changeType|timestampEpochMilli</pre>
 * The String fields (category, status, changeType) are URL-encoded (UTF-8) so they can never contain
 * the {@code |} delimiter.
 */
public final class ReportChangedEventCodec implements MessageCodec<ReportChangedEvent> {

    /** Routing key for this payload within the envelope. */
    public static final String MESSAGE_TYPE = "report.changed";

    /** Shared stateless instance — register this in the protocol. */
    public static final ReportChangedEventCodec INSTANCE = new ReportChangedEventCodec();

    private static final String SEP = "|";
    private static final int PARTS = 7;

    public ReportChangedEventCodec() {}

    @Override
    public String messageType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String encodePayload(ReportChangedEvent e) {
        return String.join(SEP,
                e.reportId().toString(),
                e.reporter().toString(),
                e.target().toString(),
                enc(e.category()),
                enc(e.status()),
                enc(e.changeType()),
                Long.toString(e.timestampEpochMilli()));
    }

    @Override
    public ReportChangedEvent decodePayload(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String[] p = payload.split("\\" + SEP, -1);
        if (p.length != PARTS) {
            throw new IllegalArgumentException("expected " + PARTS + " parts, got " + p.length + ": " + payload);
        }
        try {
            return new ReportChangedEvent(
                    UUID.fromString(p[0]),
                    UUID.fromString(p[1]),
                    UUID.fromString(p[2]),
                    dec(p[3]),
                    dec(p[4]),
                    dec(p[5]),
                    Long.parseLong(p[6]));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed report payload: " + payload, ex);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
