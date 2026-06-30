package com.mcplatform.application.economy;

import java.util.UUID;

/**
 * A suspiciously high economy movement detected by {@link EconomyAlertMonitor}. Framework-/protocol-free;
 * the app-module adapter maps it to the wire {@code EconomyAlertEvent} for publishing.
 *
 * <p>{@code player} is the subject (the sender for a transfer); {@code target} is the counterparty
 * ("An") and is non-null only for transfers, so a transfer alert carries both Von and An.
 */
public record EconomyAlert(
        UUID player,
        UUID target,
        String currency,
        String type,
        long amount,
        long balanceAfter,
        long circulation,
        String reason,
        long timestampEpochMilli) {
}
