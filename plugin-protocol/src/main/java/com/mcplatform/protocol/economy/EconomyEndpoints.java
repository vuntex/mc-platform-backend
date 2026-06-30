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

    private EconomyEndpoints() {}
}
