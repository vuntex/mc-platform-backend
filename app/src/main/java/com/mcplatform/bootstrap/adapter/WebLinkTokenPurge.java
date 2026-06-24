package com.mcplatform.bootstrap.adapter;

import com.mcplatform.application.webauth.port.LinkTokenRepository;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hygiene only — NOT a security boundary. Expired tokens are already rejected at redemption by the
 * {@code expires_at > now} filter (spec FR-014); this scheduled sweep merely keeps the table from
 * growing unbounded (FR-023). Reuses the existing {@code @EnableScheduling} (SchedulingConfig); the
 * interval is configurable.
 */
@Component
public class WebLinkTokenPurge {

    private final LinkTokenRepository tokens;
    private final Clock clock;

    public WebLinkTokenPurge(LinkTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mcplatform.webauth.purge-interval-ms:3600000}")
    public void purgeExpired() {
        tokens.deleteExpired(clock.instant());
    }
}
