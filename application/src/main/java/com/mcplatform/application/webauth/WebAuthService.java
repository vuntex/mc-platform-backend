package com.mcplatform.application.webauth;

import com.mcplatform.application.webauth.port.LinkTokenRepository;
import com.mcplatform.application.webauth.port.RedeemOutcome;
import com.mcplatform.application.webauth.port.WebAccountExistsException;
import com.mcplatform.application.webauth.port.WebAccountMissingException;
import com.mcplatform.application.webauth.port.WebAccountRepository;
import com.mcplatform.application.webauth.port.WebAuthCooldownException;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.webauth.PasswordHasher;
import com.mcplatform.domain.webauth.PasswordPolicy;
import com.mcplatform.domain.webauth.TokenGenerator;
import com.mcplatform.domain.webauth.TokenPurpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Application use case for the web-auth bridge. The web account is anchored to the player UUID; the
 * {@code /web} commands run in-game (the plugin already holds the UUID), so there is no name lookup here.
 * All preconditions, the password policy and token validity are enforced backend-side (FR-021/-019).
 *
 * <p>Token requests generate a high-entropy raw token, persist only its hash (via the repository) under
 * a one-per-(uuid, purpose) rule, and return the raw value for the clickable link. Redemption validates
 * and hashes the password, then performs the single-use account write atomically in the repository
 * (lookup + write + token delete + audit in one transaction). No live event / pub-sub for this feature.
 */
public final class WebAuthService {

    private final WebAccountRepository accounts;
    private final LinkTokenRepository tokens;
    private final PasswordHasher hasher;
    private final TokenGenerator generator;
    private final Clock clock;
    private final Duration cooldown;
    private final Duration ttl;

    public WebAuthService(WebAccountRepository accounts, LinkTokenRepository tokens, PasswordHasher hasher,
            TokenGenerator generator, Clock clock, Duration cooldown, Duration ttl) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.hasher = hasher;
        this.generator = generator;
        this.clock = clock;
        this.cooldown = cooldown;
        this.ttl = ttl;
    }

    /** Request a LINK token. Precondition: no account exists yet (else 409). Enforces the cooldown (429). */
    public TokenResult requestLinkToken(PlayerId playerUuid) {
        if (accounts.exists(playerUuid)) {
            throw new WebAccountExistsException("web account already exists; use resetPassword");
        }
        return issue(playerUuid, TokenPurpose.LINK);
    }

    /** Request a RESET token. Precondition: an account exists (else 409). Enforces the cooldown (429). */
    public TokenResult requestResetToken(PlayerId playerUuid) {
        if (!accounts.exists(playerUuid)) {
            throw new WebAccountMissingException("no web account; use link");
        }
        return issue(playerUuid, TokenPurpose.RESET);
    }

    /**
     * Redeem a raw token with a new password. Validates the password policy first, hashes it, then lets
     * the repository perform the atomic single-use redemption. Token invalidity (410), password policy
     * (422) and account-state races (409) surface as exceptions.
     */
    public RedeemOutcome redeem(String rawToken, String rawPassword) {
        PasswordPolicy.validate(rawPassword);
        String passwordHash = hasher.hash(rawPassword);
        return accounts.redeem(rawToken, passwordHash, clock.instant());
    }

    private TokenResult issue(PlayerId playerUuid, TokenPurpose purpose) {
        Instant now = clock.instant();
        enforceCooldown(playerUuid, purpose, now);
        String raw = generator.newToken();
        Instant expiresAt = now.plus(ttl);
        tokens.issue(raw, playerUuid, purpose, expiresAt, now);
        return new TokenResult(raw, purpose, expiresAt);
    }

    private void enforceCooldown(PlayerId playerUuid, TokenPurpose purpose, Instant now) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return;
        }
        tokens.lastCreatedAt(playerUuid, purpose).ifPresent(last -> {
            if (now.isBefore(last.plus(cooldown))) {
                throw new WebAuthCooldownException("web-auth cooldown active; wait before requesting another token");
            }
        });
    }
}
