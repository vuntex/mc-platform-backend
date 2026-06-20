package com.mcplatform.domain.punishment;

/**
 * Event types of the event-sourced punishment history. Expiry is NOT an event — it is derived from
 * {@code expiresAt} and {@code now()} when "active" is evaluated.
 */
public enum PunishmentEventType {
    /** A punishment was issued. */
    ISSUED,
    /** A previously issued punishment was lifted before its natural expiry. */
    REVOKED
}
