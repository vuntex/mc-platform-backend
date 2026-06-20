package com.mcplatform.application.economy.port;

/** Optimistic-lock conflict: the balance projection changed between read and write. Retryable. */
public class ConcurrencyConflictException extends RuntimeException {

    public ConcurrencyConflictException(String message) {
        super(message);
    }
}
