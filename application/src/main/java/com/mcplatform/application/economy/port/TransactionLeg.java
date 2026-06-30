package com.mcplatform.application.economy.port;

import com.mcplatform.domain.economy.EconomyEventType;
import com.mcplatform.domain.economy.Money;
import java.util.UUID;

/**
 * One side of a transaction (spec 007, US3): a single event has one leg; a transfer has two (the
 * TRANSFER_OUT sender and the TRANSFER_IN receiver), each with its player's resolved display name.
 */
public record TransactionLeg(UUID playerUuid, String playerName, EconomyEventType eventType, Money balanceAfter) {
}
