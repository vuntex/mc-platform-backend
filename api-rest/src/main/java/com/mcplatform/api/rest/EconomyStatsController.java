package com.mcplatform.api.rest;

import com.mcplatform.application.economy.EconomyStatsService;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.protocol.economy.EconomyStatsResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/economy/stats/{currency}} — total money in circulation + account count for a currency.
 * Served from the periodically-refreshed cache ({@link EconomyStatsService}); returns zeros if the
 * snapshot has no entry for the currency yet. {@code permitAll} per {@code SecurityConfig}.
 */
@RestController
public class EconomyStatsController {

    private final EconomyStatsService stats;

    public EconomyStatsController(EconomyStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/api/economy/stats/{currency}")
    public EconomyStatsResponse stats(@PathVariable String currency) {
        return stats.get(CurrencyCode.of(currency))
                .map(s -> new EconomyStatsResponse(s.currency().value(), s.total(), s.accounts()))
                .orElse(new EconomyStatsResponse(currency, 0L, 0));
    }
}
