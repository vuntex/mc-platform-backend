package com.mcplatform.application.webauth.port;

/** Raised when {@code /web link} is requested but a web account already exists. Maps to HTTP 409. */
public final class WebAccountExistsException extends RuntimeException {

    public WebAccountExistsException(String message) {
        super(message);
    }
}
