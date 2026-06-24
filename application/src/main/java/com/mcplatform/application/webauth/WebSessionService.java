package com.mcplatform.application.webauth;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.webauth.port.InvalidCredentialsException;
import com.mcplatform.application.webauth.port.RefreshTokenInvalidException;
import com.mcplatform.application.webauth.port.RefreshTokenRepository;
import com.mcplatform.application.webauth.port.RefreshTokenReuseException;
import com.mcplatform.application.webauth.port.RotateResult;
import com.mcplatform.application.webauth.port.TokenIssuer;
import com.mcplatform.application.webauth.port.WebAccountRepository;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.webauth.PasswordHasher;
import com.mcplatform.domain.webauth.TokenGenerator;
import com.mcplatform.domain.webauth.WebAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Application use case for the running web-login session. Reuses the bridge building blocks: name→UUID
 * (PlayerRepository), the web account (WebAccountRepository), BCrypt matching (PasswordHasher) and the
 * SecureRandom token generator (TokenGenerator). The access token is minted via the {@link TokenIssuer}
 * port (identity only — no rights); the rotating refresh token is state-stored via
 * {@link RefreshTokenRepository}. Authorization is NOT done here — rights come from the PermissionResolver
 * at request time on protected endpoints (Constitution §12).
 *
 * <p>Login failures (unknown name / no account / wrong password) all surface as the SAME
 * {@link InvalidCredentialsException} so an outsider cannot tell which part was wrong (D3, no enumeration).
 */
public final class WebSessionService {

    private final PlayerRepository players;
    private final WebAccountRepository accounts;
    private final PasswordHasher hasher;
    private final TokenIssuer accessTokens;
    private final TokenGenerator refreshTokenGenerator;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public WebSessionService(PlayerRepository players, WebAccountRepository accounts, PasswordHasher hasher,
            TokenIssuer accessTokens, TokenGenerator refreshTokenGenerator, RefreshTokenRepository refreshTokens,
            Clock clock, Duration accessTtl, Duration refreshTtl) {
        this.players = players;
        this.accounts = accounts;
        this.hasher = hasher;
        this.accessTokens = accessTokens;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    /** Authenticate name + password and issue a session. Any failure → uniform {@link InvalidCredentialsException}. */
    public SessionTokens login(String username, String rawPassword) {
        PlayerId uuid = players.findUuidByName(username)
                .orElseThrow(() -> new InvalidCredentialsException("invalid credentials"));
        WebAccount account = accounts.find(uuid)
                .orElseThrow(() -> new InvalidCredentialsException("invalid credentials"));
        if (!hasher.matches(rawPassword, account.passwordHash())) {
            throw new InvalidCredentialsException("invalid credentials");
        }
        return issue(uuid);
    }

    /**
     * Rotate the presented refresh token. Active → new access + new refresh; already-rotated → theft
     * signal ({@link RefreshTokenReuseException}, all sessions killed); unknown/expired →
     * {@link RefreshTokenInvalidException}. Uniform errors (no existence leak).
     */
    public SessionTokens refresh(String presentedRawRefresh) {
        Instant now = clock.instant();
        String newRefreshRaw = refreshTokenGenerator.newToken();
        Instant refreshExpiresAt = now.plus(refreshTtl);
        RotateResult result = refreshTokens.rotate(presentedRawRefresh, newRefreshRaw, now, refreshExpiresAt);
        return switch (result) {
            case RotateResult.Rotated r -> {
                String access = accessTokens.issue(r.player(), accessTtl, now);
                yield new SessionTokens(access, now.plus(accessTtl), newRefreshRaw, refreshExpiresAt);
            }
            case RotateResult.Replay r -> throw new RefreshTokenReuseException("refresh token reuse detected");
            case RotateResult.Invalid i -> throw new RefreshTokenInvalidException("refresh token invalid");
        };
    }

    /** End the current session by invalidating the presented refresh token. Idempotent (null/blank/absent → no-op). */
    public void logout(String presentedRawRefresh) {
        if (presentedRawRefresh != null && !presentedRawRefresh.isBlank()) {
            refreshTokens.deleteByRawToken(presentedRawRefresh);
        }
    }

    private SessionTokens issue(PlayerId uuid) {
        Instant now = clock.instant();
        String access = accessTokens.issue(uuid, accessTtl, now);
        String refreshRaw = refreshTokenGenerator.newToken();
        Instant refreshExpiresAt = now.plus(refreshTtl);
        refreshTokens.store(refreshRaw, uuid, now, refreshExpiresAt);
        return new SessionTokens(access, now.plus(accessTtl), refreshRaw, refreshExpiresAt);
    }
}
