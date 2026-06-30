package com.mcplatform.protocol.economy;

import com.mcplatform.protocol.core.EndpointDescriptor;
import com.mcplatform.protocol.core.HttpMethod;

/**
 * The economy REST endpoints as constants, so clients reference them by name instead of hard-coding
 * paths/methods/types. Path templates take {@code uuid} (player) and {@code currency}; for transfer
 * the receiving player is in the {@link TransferRequest} body.
 */
public final class EconomyEndpoints {

    private static final String BASE = "/api/players/{uuid}/balances/{currency}";

    /** GET current balance. */
    public static final EndpointDescriptor<Void, BalanceResponse> GET_BALANCE =
            new EndpointDescriptor<>(HttpMethod.GET, BASE, Void.class, BalanceResponse.class);

    /**
     * GET a page of the player's economy history (read-only audit trail), newest-first. Optional query
     * params: {@code currency}, {@code type} (event type), {@code before} (keyset cursor =
     * {@code nextCursor} of the previous page), {@code limit}.
     */
    public static final EndpointDescriptor<Void, EconomyHistoryResponse> GET_HISTORY =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/players/{uuid}/economy/history",
                    Void.class, EconomyHistoryResponse.class);

    /** POST credit (add coins). */
    public static final EndpointDescriptor<AmountRequest, BalanceResponse> CREDIT =
            new EndpointDescriptor<>(HttpMethod.POST, BASE + "/credit", AmountRequest.class, BalanceResponse.class);

    /** POST debit (subtract coins; funds-checked server-side). */
    public static final EndpointDescriptor<AmountRequest, BalanceResponse> DEBIT =
            new EndpointDescriptor<>(HttpMethod.POST, BASE + "/debit", AmountRequest.class, BalanceResponse.class);

    /** POST set (admin override of the absolute balance). */
    public static final EndpointDescriptor<AmountRequest, BalanceResponse> SET =
            new EndpointDescriptor<>(HttpMethod.POST, BASE + "/set", AmountRequest.class, BalanceResponse.class);

    /** POST transfer from the path player to {@link TransferRequest#to()}. */
    public static final EndpointDescriptor<TransferRequest, TransferResponse> TRANSFER =
            new EndpointDescriptor<>(HttpMethod.POST, BASE + "/transfer", TransferRequest.class, TransferResponse.class);

    /** GET total money in circulation + account count for a currency. */
    public static final EndpointDescriptor<Void, EconomyStatsResponse> STATS =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/economy/stats/{currency}",
                    Void.class, EconomyStatsResponse.class);

    // --- web-interface-only read surface (spec 007), behind the /api/web/** JWT chain ---------------

    /** GET all of a player's balances (every currency) in one call, with currency display metadata. */
    public static final EndpointDescriptor<Void, PlayerBalancesResponse> LIST_BALANCES =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/web/economy/players/{uuid}/balances",
                    Void.class, PlayerBalancesResponse.class);

    /**
     * GET the server-wide economy history (all players), newest-first. Optional query params:
     * {@code currency}, {@code type}, {@code source}, {@code before} (keyset cursor), {@code limit}.
     */
    public static final EndpointDescriptor<Void, EconomyHistoryResponse> SERVER_HISTORY =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/web/economy/history",
                    Void.class, EconomyHistoryResponse.class);

    /** GET one transaction's detail by its business {@code transactionId} (single event or transfer). */
    public static final EndpointDescriptor<Void, TransactionDetailResponse> GET_TRANSACTION =
            new EndpointDescriptor<>(HttpMethod.GET, "/api/web/economy/transactions/{transactionId}",
                    Void.class, TransactionDetailResponse.class);

    private EconomyEndpoints() {}
}
