package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.EconomyMapper;
import com.mcplatform.application.economy.EconomyPermissions;
import com.mcplatform.application.economy.ServerHistoryQuery;
import com.mcplatform.application.economy.TransactionDetailQuery;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.economy.EconomyHistoryResponse;
import com.mcplatform.protocol.economy.TransactionDetailResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web-interface-only read surface for the server-wide economy history ({@code GET /api/web/economy/history},
 * spec 007 US2). Behind the {@code /api/web/**} JWT chain (401 without a token); gated against
 * {@code permission.economy.read} via the {@link PermissionResolver} (403). Read-only, newest-first,
 * keyset-paginated. An unknown {@code type} or a non-positive {@code limit} is a 400 via the existing
 * {@code EconomyExceptionHandler} (IllegalArgumentException mapping); {@code source} is a free-form filter.
 */
@RestController
public class ServerEconomyController {

    private final ServerHistoryQuery query;
    private final TransactionDetailQuery transactions;
    private final PermissionResolver resolver;

    public ServerEconomyController(ServerHistoryQuery query, TransactionDetailQuery transactions,
            PermissionResolver resolver) {
        this.query = query;
        this.transactions = transactions;
        this.resolver = resolver;
    }

    @GetMapping("/api/web/economy/history")
    public EconomyHistoryResponse history(@AuthenticationPrincipal PlayerId actor,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Integer limit) {
        requireEconomyRead(actor);
        return EconomyMapper.serverHistoryResponse(query.history(
                EconomyMapper.currencyFilter(currency),
                EconomyMapper.eventTypeFilter(type),
                EconomyMapper.sourceFilter(source),
                before,
                limit));
    }

    @GetMapping("/api/web/economy/transactions/{transactionId}")
    public TransactionDetailResponse transaction(@AuthenticationPrincipal PlayerId actor,
            @PathVariable UUID transactionId) {
        requireEconomyRead(actor);
        return EconomyMapper.transactionDetail(transactions.detail(TransactionId.of(transactionId)));
    }

    private void requireEconomyRead(PlayerId actor) {
        if (!resolver.hasPermission(actor.value(), EconomyPermissions.READ)) {
            throw new PermissionDeniedException(actor.value(), EconomyPermissions.READ);
        }
    }
}
