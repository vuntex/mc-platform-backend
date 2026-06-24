package com.mcplatform.protocol.webauth;

/**
 * Request body sent by the webinterface to redeem a token — shared REST contract (JDK only).
 * {@code password} is the new plaintext password (over TLS); the backend hashes it immediately and
 * never stores or logs it in clear.
 */
public record RedeemRequest(String token, String password) {
}
