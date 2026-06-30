package com.mcplatform.protocol.economy;

import java.util.UUID;

/**
 * A suspiciously high economy movement, published for admin broadcast + logging. {@code playerUuid} is
 * the subject ("Von" — the sender for a transfer); {@code targetUuid} is the counterparty ("An") and is
 * non-null only for transfers. {@code reason} is human-readable (e.g. "12% des Umlaufs"); {@code
 * circulation} is the total money in circulation for the currency at evaluation time. Pure data (JDK
 * only); field names are the wire contract.
 */
public record EconomyAlertEvent(
        UUID playerUuid,
        UUID targetUuid,
        String currencyCode,
        String eventType,
        long amount,
        long balanceAfter,
        long circulation,
        String reason,
        long timestampEpochMilli) {
}
