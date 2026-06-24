package com.mcplatform.application.webauth.port;

/** Raised when tokens are requested faster than the configured cooldown allows (spec FR-022). Maps to HTTP 429. */
public final class WebAuthCooldownException extends RuntimeException {

    public WebAuthCooldownException(String message) {
        super(message);
    }
}
