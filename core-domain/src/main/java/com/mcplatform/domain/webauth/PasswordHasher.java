package com.mcplatform.domain.webauth;

/**
 * Outbound port for irreversible password hashing. The implementation (BCrypt) lives in the {@code app}
 * composition root so {@code core-domain} stays framework-free and {@code infra-persistence} stays
 * Spring-free (plan R4). The hash must never leak into core-domain or the shared protocol.
 *
 * <p>{@code matches} is part of the contract for the later login slice; this slice only exercises
 * {@code hash}.
 */
public interface PasswordHasher {

    /** Hash a plaintext password into an irreversible, salted digest for storage. */
    String hash(String raw);

    /** Whether {@code raw} matches a previously stored {@code hash}. */
    boolean matches(String raw, String hash);
}
