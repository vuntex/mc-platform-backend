package com.mcplatform.application.permission;

/**
 * Thrown when an operation would leave zero accounts holding a role at the current maximum weight —
 * i.e. it would remove the last top-tier member and make the system unmanageable (spec 008, FR-015).
 * Mapped to HTTP 409 {@code last_top_tier}.
 */
public final class LastTopTierException extends RuntimeException {

    public LastTopTierException(String message) {
        super(message);
    }
}
