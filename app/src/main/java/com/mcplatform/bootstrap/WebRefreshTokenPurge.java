package com.mcplatform.bootstrap;

import com.mcplatform.application.webauth.port.RefreshTokenRepository;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hygiene: periodically delete expired refresh tokens (FR-019). Not security-critical for correctness —
 * rotation/refresh already reject expired tokens — it just keeps the table tidy. Runs on the existing
 * {@code @EnableScheduling} (permission slice). Mirrors the bridge's expired-link-token purge.
 */
@Component
public class WebRefreshTokenPurge {

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public WebRefreshTokenPurge(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${mcplatform.webauth.refresh-purge-interval-ms:3600000}")
    public void purgeExpired() {
        refreshTokens.purgeExpired(clock.instant());
    }
}
