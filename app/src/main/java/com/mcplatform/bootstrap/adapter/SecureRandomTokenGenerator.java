package com.mcplatform.bootstrap.adapter;

import com.mcplatform.domain.webauth.TokenGenerator;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * {@link TokenGenerator} backed by {@link SecureRandom}. Emits 256 bits of entropy (well above the
 * ≥128-bit requirement, spec FR-024) as a URL-safe, unpadded Base64 string — short enough to ride in a
 * clickable chat link and never typed by the player (FR-025).
 */
public final class SecureRandomTokenGenerator implements TokenGenerator {

    private static final int TOKEN_BYTES = 32; // 256 bit

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    @Override
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
