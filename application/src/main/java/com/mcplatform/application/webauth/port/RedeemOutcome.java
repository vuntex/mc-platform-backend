package com.mcplatform.application.webauth.port;

/** Result of a successful token redemption: a new account was created, or a password was reset. */
public enum RedeemOutcome {
    LINK_CREATED,
    RESET_DONE
}
