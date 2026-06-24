package com.mcplatform.bootstrap.config;

import com.mcplatform.application.webauth.WebAuthService;
import com.mcplatform.application.webauth.port.LinkTokenRepository;
import com.mcplatform.application.webauth.port.WebAccountRepository;
import com.mcplatform.bootstrap.adapter.BCryptPasswordHasher;
import com.mcplatform.bootstrap.adapter.SecureRandomTokenGenerator;
import com.mcplatform.domain.webauth.PasswordHasher;
import com.mcplatform.domain.webauth.TokenGenerator;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root for the web-auth bridge. Binds the password hasher (BCrypt) and token generator
 * (SecureRandom) adapters and the use case, reusing the existing {@code Clock} bean. No publisher / no
 * Redis — this feature has no live path.
 */
@Configuration
public class WebAuthConfig {

    @Bean
    PasswordHasher passwordHasher() {
        return new BCryptPasswordHasher();
    }

    @Bean
    TokenGenerator tokenGenerator() {
        return new SecureRandomTokenGenerator();
    }

    @Bean
    WebAuthService webAuthService(WebAccountRepository accounts, LinkTokenRepository tokens,
            PasswordHasher hasher, TokenGenerator generator, Clock clock,
            @Value("${mcplatform.webauth.token-cooldown-seconds:60}") long cooldownSeconds,
            @Value("${mcplatform.webauth.token-ttl-minutes:10}") long ttlMinutes) {
        return new WebAuthService(accounts, tokens, hasher, generator, clock,
                Duration.ofSeconds(cooldownSeconds), Duration.ofMinutes(ttlMinutes));
    }
}
