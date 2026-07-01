package com.mcplatform.application.webauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebSessionServiceTest {

    private final PlayerId player = PlayerId.of(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-06-24T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Duration accessTtl = Duration.ofMinutes(15);
    private final Duration refreshTtl = Duration.ofDays(30);

    private WebSessionService service(FakePlayers players, FakeAccounts accounts, FakeRefresh refresh) {
        return new WebSessionService(players, accounts, new FakeHasher(), new FakeIssuer("access-jwt"),
                new FakeGenerator("refresh-raw"), refresh, clock, accessTtl, refreshTtl);
    }

    // --- login ------------------------------------------------------------

    @Test
    void loginIssuesAccessAndRefreshOnCorrectCredentials() {
        FakeRefresh refresh = new FakeRefresh();
        SessionTokens tokens = service(new FakePlayers(player), new FakeAccounts("h:secret123"), refresh)
                .login("Vuntex", "secret123");

        assertThat(tokens.accessToken()).isEqualTo("access-jwt");
        assertThat(tokens.accessExpiresAt()).isEqualTo(now.plus(accessTtl));
        assertThat(tokens.refreshRawToken()).isEqualTo("refresh-raw");
        assertThat(tokens.refreshExpiresAt()).isEqualTo(now.plus(refreshTtl));
        assertThat(refresh.stored).isEqualTo(1);
        assertThat(refresh.lastStoredRaw).isEqualTo("refresh-raw");
        assertThat(refresh.lastStoredPlayer).isEqualTo(player);
    }

    @Test
    void loginWithUnknownNameFailsUniformly() {
        FakeRefresh refresh = new FakeRefresh();
        assertThatThrownBy(() -> service(new FakePlayers(null), new FakeAccounts("h:x"), refresh)
                .login("Ghost", "secret123"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(refresh.stored).isZero();
    }

    @Test
    void loginWithoutWebAccountFailsUniformly() {
        FakeRefresh refresh = new FakeRefresh();
        assertThatThrownBy(() -> service(new FakePlayers(player), new FakeAccounts(null), refresh)
                .login("Vuntex", "secret123"))
                .isInstanceOf(InvalidCredentialsException.class); // same type as wrong password (D3)
        assertThat(refresh.stored).isZero();
    }

    @Test
    void loginWithWrongPasswordFailsUniformly() {
        FakeRefresh refresh = new FakeRefresh();
        assertThatThrownBy(() -> service(new FakePlayers(player), new FakeAccounts("h:other"), refresh)
                .login("Vuntex", "secret123"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(refresh.stored).isZero();
    }

    // --- refresh ----------------------------------------------------------

    @Test
    void refreshRotatesAndIssuesNewPair() {
        FakeRefresh refresh = new FakeRefresh();
        refresh.result = new RotateResult.Rotated(player);
        SessionTokens tokens = service(new FakePlayers(player), new FakeAccounts("h:x"), refresh)
                .refresh("old-refresh");

        assertThat(tokens.accessToken()).isEqualTo("access-jwt");
        assertThat(tokens.refreshRawToken()).isEqualTo("refresh-raw"); // newly generated successor
        assertThat(refresh.lastPresented).isEqualTo("old-refresh");
        assertThat(refresh.lastNewRaw).isEqualTo("refresh-raw");
        assertThat(refresh.lastNewExpiresAt).isEqualTo(now.plus(refreshTtl));
    }

    @Test
    void refreshWithInvalidTokenThrowsInvalid() {
        FakeRefresh refresh = new FakeRefresh();
        refresh.result = new RotateResult.Invalid();
        assertThatThrownBy(() -> service(new FakePlayers(player), new FakeAccounts("h:x"), refresh)
                .refresh("expired-or-unknown"))
                .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void refreshWithReplayThrowsReuse() {
        FakeRefresh refresh = new FakeRefresh();
        refresh.result = new RotateResult.Replay(player);
        assertThatThrownBy(() -> service(new FakePlayers(player), new FakeAccounts("h:x"), refresh)
                .refresh("already-rotated"))
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    // --- logout -----------------------------------------------------------

    @Test
    void logoutDeletesPresentedToken() {
        FakeRefresh refresh = new FakeRefresh();
        service(new FakePlayers(player), new FakeAccounts("h:x"), refresh).logout("the-refresh");
        assertThat(refresh.lastDeletedRaw).isEqualTo("the-refresh");
    }

    @Test
    void logoutIsIdempotentForBlankToken() {
        FakeRefresh refresh = new FakeRefresh();
        service(new FakePlayers(player), new FakeAccounts("h:x"), refresh).logout("  ");
        assertThat(refresh.lastDeletedRaw).isNull(); // never touched the repo
    }

    // --- fakes ------------------------------------------------------------

    private static final class FakePlayers implements PlayerRepository {
        private final PlayerId resolved;

        FakePlayers(PlayerId resolved) {
            this.resolved = resolved;
        }

        @Override
        public Optional<PlayerId> findUuidByName(String name) {
            return Optional.ofNullable(resolved);
        }

        @Override
        public Optional<String> findNameByUuid(PlayerId player) {
            return Optional.empty();
        }

        @Override
        public java.util.List<PlayerNameMatch> searchByNamePrefix(String prefix, int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.Map<java.util.UUID, String> findNamesByUuids(java.util.Collection<java.util.UUID> uuids) {
            return java.util.Map.of();
        }

        @Override
        public void save(PlayerId player, String name, Instant seenAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean upsertReturningWhetherNew(PlayerId player, String name, Instant seenAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void touchLastSeen(PlayerId player, Instant seenAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countRegisteredSince(Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<PlayerLastSeen> findRecentOnline(java.util.Collection<java.util.UUID> uuids, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<PlayerLastSeen> findRecentExcluding(java.util.Collection<java.util.UUID> exclude, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAccounts implements WebAccountRepository {
        private final String passwordHash; // null = no account

        FakeAccounts(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        @Override
        public Optional<WebAccount> find(PlayerId playerUuid) {
            return passwordHash == null
                    ? Optional.empty()
                    : Optional.of(new WebAccount(playerUuid, passwordHash, Instant.EPOCH, Instant.EPOCH));
        }

        @Override
        public boolean exists(PlayerId playerUuid) {
            return passwordHash != null;
        }

        @Override
        public com.mcplatform.application.webauth.port.RedeemOutcome redeem(String r, String h, Instant n) {
            throw new UnsupportedOperationException();
        }
    }

    /** matches() returns true iff hash equals "h:" + raw — mirrors the bridge test hasher. */
    private static final class FakeHasher implements PasswordHasher {
        @Override
        public String hash(String raw) {
            return "h:" + raw;
        }

        @Override
        public boolean matches(String raw, String hash) {
            return hash.equals("h:" + raw);
        }
    }

    private static final class FakeIssuer implements TokenIssuer {
        private final String value;

        FakeIssuer(String value) {
            this.value = value;
        }

        @Override
        public String issue(PlayerId subject, Duration ttl, Instant now) {
            return value;
        }
    }

    private static final class FakeGenerator implements TokenGenerator {
        private final String value;

        FakeGenerator(String value) {
            this.value = value;
        }

        @Override
        public String newToken() {
            return value;
        }
    }

    private static final class FakeRefresh implements RefreshTokenRepository {
        int stored;
        String lastStoredRaw;
        PlayerId lastStoredPlayer;
        String lastPresented;
        String lastNewRaw;
        Instant lastNewExpiresAt;
        String lastDeletedRaw;
        RotateResult result = new RotateResult.Invalid();

        @Override
        public void store(String rawToken, PlayerId player, Instant createdAt, Instant expiresAt) {
            stored++;
            lastStoredRaw = rawToken;
            lastStoredPlayer = player;
        }

        @Override
        public RotateResult rotate(String presentedRawToken, String newRawToken, Instant now, Instant newExpiresAt) {
            lastPresented = presentedRawToken;
            lastNewRaw = newRawToken;
            lastNewExpiresAt = newExpiresAt;
            return result;
        }

        @Override
        public boolean deleteByRawToken(String rawToken) {
            lastDeletedRaw = rawToken;
            return true;
        }

        @Override
        public int deleteAllForPlayer(PlayerId player) {
            return 0;
        }

        @Override
        public int purgeExpired(Instant now) {
            return 0;
        }
    }
}
