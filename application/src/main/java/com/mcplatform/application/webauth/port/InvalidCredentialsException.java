package com.mcplatform.application.webauth.port;

/**
 * Uniform login failure: wrong password, unknown name, OR no web account (D3). Deliberately
 * indistinguishable to the caller so an outsider cannot enumerate which names have a web account.
 * Surfaces as 401 {@code web_auth_invalid_credentials}.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
