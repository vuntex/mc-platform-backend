package com.mcplatform.protocol.economy;

import com.mcplatform.protocol.core.MessageCodec;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@link MessageCodec} for {@link EconomyAlertEvent}. Dependency-free, pipe-delimited (mirrors
 * {@link BalanceChangedEventCodec}), exactly 9 parts:
 * <pre>playerUuid|targetUuid|currencyCode|eventType|amount|balanceAfter|circulation|reason|timestampEpochMilli</pre>
 * String fields (currencyCode, eventType, reason) are URL-encoded (UTF-8) so they never contain the
 * {@code |} delimiter; {@code targetUuid} is empty when absent (non-transfer).
 */
public final class EconomyAlertEventCodec implements MessageCodec<EconomyAlertEvent> {

    public static final String MESSAGE_TYPE = "economy.alert";
    public static final EconomyAlertEventCodec INSTANCE = new EconomyAlertEventCodec();

    private static final String SEP = "|";
    private static final int PARTS = 9;

    public EconomyAlertEventCodec() {
    }

    @Override
    public String messageType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String encodePayload(EconomyAlertEvent e) {
        return String.join(SEP,
                e.playerUuid().toString(),
                e.targetUuid() == null ? "" : e.targetUuid().toString(),
                enc(e.currencyCode()),
                enc(e.eventType()),
                Long.toString(e.amount()),
                Long.toString(e.balanceAfter()),
                Long.toString(e.circulation()),
                enc(e.reason()),
                Long.toString(e.timestampEpochMilli()));
    }

    @Override
    public EconomyAlertEvent decodePayload(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String[] p = payload.split("\\" + SEP, -1);
        if (p.length != PARTS) {
            throw new IllegalArgumentException("expected " + PARTS + " parts, got " + p.length + ": " + payload);
        }
        try {
            return new EconomyAlertEvent(
                    UUID.fromString(p[0]),
                    p[1].isEmpty() ? null : UUID.fromString(p[1]),
                    dec(p[2]),
                    dec(p[3]),
                    Long.parseLong(p[4]),
                    Long.parseLong(p[5]),
                    Long.parseLong(p[6]),
                    dec(p[7]),
                    Long.parseLong(p[8]));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("malformed economy alert payload: " + payload, ex);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
