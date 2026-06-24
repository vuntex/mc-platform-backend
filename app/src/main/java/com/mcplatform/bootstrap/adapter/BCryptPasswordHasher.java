package com.mcplatform.bootstrap.adapter;

import com.mcplatform.domain.webauth.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt implementation of the {@link PasswordHasher} port. Lives in the {@code app} composition root —
 * the only module allowed to depend on {@code spring-security-crypto} — so {@code core-domain} stays
 * framework-free and {@code infra-persistence} stays Spring-free (plan R4). The plaintext password is
 * hashed here and never persisted or logged in clear.
 */
public final class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String raw) {
        return encoder.encode(raw);
    }

    @Override
    public boolean matches(String raw, String hash) {
        return encoder.matches(raw, hash);
    }
}
