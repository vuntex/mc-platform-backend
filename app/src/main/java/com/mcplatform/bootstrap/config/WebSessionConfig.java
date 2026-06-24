package com.mcplatform.bootstrap.config;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.webauth.WebSessionService;
import com.mcplatform.application.webauth.port.RefreshTokenRepository;
import com.mcplatform.application.webauth.port.TokenIssuer;
import com.mcplatform.application.webauth.port.WebAccountRepository;
import com.mcplatform.bootstrap.adapter.JwtTokenService;
import com.mcplatform.domain.webauth.PasswordHasher;
import com.mcplatform.domain.webauth.TokenGenerator;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the JWT-login-session slice. The single {@link JwtTokenService} bean (jjwt/HS256)
 * serves BOTH the {@code TokenIssuer} and {@code TokenVerifier} ports (it implements both), so the JWT
 * library stays in {@code app}. {@link WebSessionService} reuses the bridge's {@code PasswordHasher} and
 * {@code TokenGenerator} beans plus the {@code Clock}. TTLs come from configuration.
 */
@Configuration
public class WebSessionConfig {

    @Bean
    JwtTokenService jwtTokenService(@Value("${mcplatform.webauth.jwt.secret:}") String secret) {
        return new JwtTokenService(secret); // fails fast if the secret is missing / under 256 bits
    }

    @Bean
    WebSessionService webSessionService(PlayerRepository players, WebAccountRepository accounts,
            PasswordHasher hasher, TokenIssuer accessTokens, TokenGenerator tokenGenerator,
            RefreshTokenRepository refreshTokens, Clock clock,
            @Value("${mcplatform.webauth.access-ttl-minutes:15}") long accessTtlMinutes,
            @Value("${mcplatform.webauth.refresh-ttl-days:30}") long refreshTtlDays) {
        return new WebSessionService(players, accounts, hasher, accessTokens, tokenGenerator, refreshTokens,
                clock, Duration.ofMinutes(accessTtlMinutes), Duration.ofDays(refreshTtlDays));
    }
}
