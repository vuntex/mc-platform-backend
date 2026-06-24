package com.mcplatform.application.webauth.port;

/** Raised when {@code /web resetPassword} is requested but no web account exists. Maps to HTTP 409. */
public final class WebAccountMissingException extends RuntimeException {

    public WebAccountMissingException(String message) {
        super(message);
    }
}
