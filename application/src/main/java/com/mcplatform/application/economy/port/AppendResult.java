package com.mcplatform.application.economy.port;

import com.mcplatform.domain.economy.Money;
import java.time.Instant;

/**
 * Outcome of {@link EconomyEventStore#append}.
 *
 * @param version         sequence_no assigned to the event (the new projection version)
 * @param balanceAfter    balance after the event was applied
 * @param occurredAt      when the event was recorded (DB time)
 * @param idempotentReplay true if this transactionId already existed and nothing new was written
 */
public record AppendResult(long version, Money balanceAfter, Instant occurredAt, boolean idempotentReplay) {
}
