package com.mcplatform.domain.economy;

/**
 * Event types of the event-sourced economy (PROGRESS.md section 7).
 * {@code amount} is always stored positive; the direction is encoded by the type.
 */
public enum EconomyEventType {
    /** Coins credited — positive, added. */
    CREDITED,
    /** Coins debited — positive, subtracted (requires balance check). */
    DEBITED,
    /** Balance set directly by an admin — absolute new value. */
    SET,
    /** Sent to another player — positive, subtracted. */
    TRANSFER_OUT,
    /** Received from another player — positive, added. */
    TRANSFER_IN
}
