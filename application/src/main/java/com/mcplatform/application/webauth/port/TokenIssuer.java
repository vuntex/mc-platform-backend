package com.mcplatform.application.webauth.port;

import com.mcplatform.domain.player.PlayerId;
import java.time.Duration;
import java.time.Instant;

/**
 * Outbound port issuing a short-lived, signed access token. The implementation (jjwt/HS256) lives in the
 * {@code app} composition root, mirroring the BCrypt {@code PasswordHasher} — so {@code application} and
 * {@code core-domain} stay JWT-free (plan R1/R4). The token carries IDENTITY ONLY (subject = player_uuid),
 * never roles/permissions (Constitution §12).
 */
public interface TokenIssuer {

    /** Sign an access token for {@code subject}, valid for {@code ttl} starting at {@code now}. */
    String issue(PlayerId subject, Duration ttl, Instant now);
}
