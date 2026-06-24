package com.mcplatform.protocol.webauth;

/**
 * Response to a token request — shared REST contract (JDK only). {@code token} is the raw, high-entropy
 * value the plugin embeds in a clickable web link; {@code purpose} is {@code "LINK"} or {@code "RESET"}.
 * Never carries a password or any hash, and there is no email/username anywhere in this feature.
 */
public record TokenResponse(String token, String purpose, long expiresAtEpochMilli) {
}
