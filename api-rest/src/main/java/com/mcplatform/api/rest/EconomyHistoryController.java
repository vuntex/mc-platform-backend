package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.EconomyMapper;
import com.mcplatform.application.economy.EconomyHistoryService;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.economy.EconomyHistoryResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only economy audit/history surface (web interface / admin). Thin: parses the query params,
 * delegates to {@link EconomyHistoryService}, and maps the result onto the dependency-free protocol
 * DTO. An unknown player simply yields an empty page (no 404); an unknown {@code type} or non-positive
 * {@code limit} is a 400 via {@code EconomyExceptionHandler}.
 */
@RestController
@RequestMapping("/api/players/{uuid}/economy/history")
public class EconomyHistoryController {

    private final EconomyHistoryService history;

    public EconomyHistoryController(EconomyHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public EconomyHistoryResponse history(
            @PathVariable UUID uuid,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Integer limit) {
        return EconomyMapper.historyResponse(uuid, history.history(
                PlayerId.of(uuid),
                EconomyMapper.currencyFilter(currency),
                EconomyMapper.eventTypeFilter(type),
                before,
                limit));
    }
}
