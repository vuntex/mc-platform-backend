package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.webauth.port.AccessTokenInvalidException;
import com.mcplatform.application.webauth.port.TokenIssuer;
import com.mcplatform.application.webauth.port.TokenVerifier;
import com.mcplatform.domain.player.PlayerId;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * jjwt-backed HS256 implementation of {@link TokenIssuer} + {@link TokenVerifier}. Lives ONLY in the
 * {@code app} composition root (mirroring {@link BCryptPasswordHasher}) so the JWT library never leaks
 * into core-domain/application/api-rest (plan R1/R4). The token carries IDENTITY ONLY: subject =
 * player_uuid, plus issued-at/expiry — no roles/permissions (Constitution §12). The HS256 secret comes
 * from configuration (never the code); the constructor fails fast if it is missing or under 256 bits.
 */
public final class JwtTokenService implements TokenIssuer, TokenVerifier {

    private final SecretKey key;

    public JwtTokenService(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "mcplatform.webauth.jwt.secret must be set and at least 256 bits (32 bytes)");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issue(PlayerId subject, Duration ttl, Instant now) {
        return Jwts.builder()
                .subject(subject.value().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public PlayerId verify(String accessToken) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(accessToken)
                    .getPayload()
                    .getSubject();
            return PlayerId.of(UUID.fromString(subject));
        } catch (JwtException | IllegalArgumentException e) {
            // malformed, tampered signature, expired, or non-UUID subject — all uniformly invalid
            throw new AccessTokenInvalidException("access token invalid");
        }
    }
}
