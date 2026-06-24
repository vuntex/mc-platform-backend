package com.mcplatform.application.webauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.webauth.port.LinkTokenRepository;
import com.mcplatform.application.webauth.port.RedeemOutcome;
import com.mcplatform.application.webauth.port.TokenInvalidException;
import com.mcplatform.application.webauth.port.WebAccountConflictException;
import com.mcplatform.application.webauth.port.WebAccountExistsException;
import com.mcplatform.application.webauth.port.WebAccountMissingException;
import com.mcplatform.application.webauth.port.WebAccountRepository;
import com.mcplatform.application.webauth.port.WebAuthCooldownException;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.webauth.InvalidPasswordException;
import com.mcplatform.domain.webauth.PasswordHasher;
import com.mcplatform.domain.webauth.TokenGenerator;
import com.mcplatform.domain.webauth.TokenPurpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebAuthServiceTest {

    private final PlayerId player = PlayerId.of(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-06-24T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Duration cooldown = Duration.ofSeconds(60);
    private final Duration ttl = Duration.ofMinutes(10);

    private WebAuthService service(FakeAccounts accounts, FakeTokens tokens) {
        return new WebAuthService(accounts, tokens, new FakeHasher(), new FakeGenerator("tok-raw"),
                clock, cooldown, ttl);
    }

    // --- link -------------------------------------------------------------

    @Test
    void requestLinkTokenIssuesWhenNoAccount() {
        FakeTokens tokens = new FakeTokens();
        TokenResult result = service(new FakeAccounts(false), tokens).requestLinkToken(player);

        assertThat(result.purpose()).isEqualTo(TokenPurpose.LINK);
        assertThat(result.rawToken()).isEqualTo("tok-raw");
        assertThat(result.expiresAt()).isEqualTo(now.plus(ttl));
        assertThat(tokens.issued).isEqualTo(1);
        assertThat(tokens.lastPurpose).isEqualTo(TokenPurpose.LINK);
    }

    @Test
    void requestLinkTokenRejectedWhenAccountExists() {
        FakeTokens tokens = new FakeTokens();
        assertThatThrownBy(() -> service(new FakeAccounts(true), tokens).requestLinkToken(player))
                .isInstanceOf(WebAccountExistsException.class);
        assertThat(tokens.issued).isZero();
    }

    // --- reset ------------------------------------------------------------

    @Test
    void requestResetTokenIssuesWhenAccountExists() {
        FakeTokens tokens = new FakeTokens();
        TokenResult result = service(new FakeAccounts(true), tokens).requestResetToken(player);

        assertThat(result.purpose()).isEqualTo(TokenPurpose.RESET);
        assertThat(tokens.issued).isEqualTo(1);
    }

    @Test
    void requestResetTokenRejectedWhenNoAccount() {
        FakeTokens tokens = new FakeTokens();
        assertThatThrownBy(() -> service(new FakeAccounts(false), tokens).requestResetToken(player))
                .isInstanceOf(WebAccountMissingException.class);
        assertThat(tokens.issued).isZero();
    }

    // --- cooldown ---------------------------------------------------------

    @Test
    void cooldownBlocksASecondTokenTooSoon() {
        FakeTokens tokens = new FakeTokens();
        tokens.last.put(TokenPurpose.LINK, now.minusSeconds(30)); // within 60s cooldown
        assertThatThrownBy(() -> service(new FakeAccounts(false), tokens).requestLinkToken(player))
                .isInstanceOf(WebAuthCooldownException.class);
        assertThat(tokens.issued).isZero();
    }

    @Test
    void cooldownElapsedAllowsReissue() {
        FakeTokens tokens = new FakeTokens();
        tokens.last.put(TokenPurpose.LINK, now.minusSeconds(120)); // older than cooldown
        service(new FakeAccounts(false), tokens).requestLinkToken(player);
        assertThat(tokens.issued).isEqualTo(1);
    }

    // --- redeem -----------------------------------------------------------

    @Test
    void redeemRejectsTooShortPasswordBeforeTouchingRepo() {
        FakeAccounts accounts = new FakeAccounts(false);
        assertThatThrownBy(() -> service(accounts, new FakeTokens()).redeem("tok-raw", "short"))
                .isInstanceOf(InvalidPasswordException.class);
        assertThat(accounts.redeemCalls).isZero();
    }

    @Test
    void redeemHashesPasswordAndDelegates() {
        FakeAccounts accounts = new FakeAccounts(false);
        accounts.outcome = RedeemOutcome.LINK_CREATED;

        RedeemOutcome result = service(accounts, new FakeTokens()).redeem("tok-raw", "longenough");

        assertThat(result).isEqualTo(RedeemOutcome.LINK_CREATED);
        assertThat(accounts.lastHash).isEqualTo("h:longenough"); // never the plaintext
        assertThat(accounts.lastRawToken).isEqualTo("tok-raw");
    }

    @Test
    void redeemPropagatesTokenInvalid() {
        FakeAccounts accounts = new FakeAccounts(false);
        accounts.toThrow = new TokenInvalidException("nope");
        assertThatThrownBy(() -> service(accounts, new FakeTokens()).redeem("bad", "longenough"))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void redeemPropagatesAccountConflict() {
        FakeAccounts accounts = new FakeAccounts(false);
        accounts.toThrow = new WebAccountConflictException("race");
        assertThatThrownBy(() -> service(accounts, new FakeTokens()).redeem("tok-raw", "longenough"))
                .isInstanceOf(WebAccountConflictException.class);
    }

    // --- fakes ------------------------------------------------------------

    private static final class FakeAccounts implements WebAccountRepository {
        private final boolean exists;
        int redeemCalls;
        String lastRawToken;
        String lastHash;
        RedeemOutcome outcome = RedeemOutcome.LINK_CREATED;
        RuntimeException toThrow;

        FakeAccounts(boolean exists) {
            this.exists = exists;
        }

        @Override
        public boolean exists(PlayerId playerUuid) {
            return exists;
        }

        @Override
        public java.util.Optional<com.mcplatform.domain.webauth.WebAccount> find(PlayerId playerUuid) {
            return java.util.Optional.empty();
        }

        @Override
        public RedeemOutcome redeem(String rawToken, String passwordHash, Instant now) {
            redeemCalls++;
            lastRawToken = rawToken;
            lastHash = passwordHash;
            if (toThrow != null) {
                throw toThrow;
            }
            return outcome;
        }
    }

    private static final class FakeTokens implements LinkTokenRepository {
        int issued;
        TokenPurpose lastPurpose;
        final Map<TokenPurpose, Instant> last = new EnumMap<>(TokenPurpose.class);

        @Override
        public void issue(String rawToken, PlayerId playerUuid, TokenPurpose purpose, Instant expiresAt, Instant now) {
            issued++;
            lastPurpose = purpose;
        }

        @Override
        public Optional<Instant> lastCreatedAt(PlayerId playerUuid, TokenPurpose purpose) {
            return Optional.ofNullable(last.get(purpose));
        }

        @Override
        public int deleteExpired(Instant now) {
            return 0;
        }
    }

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
}
