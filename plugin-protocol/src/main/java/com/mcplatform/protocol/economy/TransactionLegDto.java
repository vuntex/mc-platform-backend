package com.mcplatform.protocol.economy;

import java.util.UUID;

/**
 * One side of a transaction on the wire (spec 007, US3): the player (uuid + resolved name), the event
 * type (CREDITED|DEBITED|SET|TRANSFER_OUT|TRANSFER_IN) and the balance after the event. Pure data
 * (JDK only); field names are the wire contract.
 */
public record TransactionLegDto(UUID playerUuid, String playerName, String eventType, long balanceAfter) {
}
