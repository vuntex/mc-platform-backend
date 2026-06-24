package com.mcplatform.domain.webauth;

/**
 * Backend-authoritative password rule (spec FR-021, clarification Q4): at least 8 and at most 64
 * characters, no enforced character classes (length over composition). The 64-char ceiling stays below
 * the 72-byte boundary of the hash algorithm (even with multi-byte UTF-8) so nothing is silently
 * truncated. Pure domain logic — no framework, no hashing here.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    private PasswordPolicy() {}

    /** Throws {@link InvalidPasswordException} if {@code raw} is null or outside the allowed length. */
    public static void validate(String raw) {
        if (raw == null || raw.length() < MIN_LENGTH) {
            throw new InvalidPasswordException("password must be at least " + MIN_LENGTH + " characters");
        }
        if (raw.length() > MAX_LENGTH) {
            throw new InvalidPasswordException("password must be at most " + MAX_LENGTH + " characters");
        }
    }
}
