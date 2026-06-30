package com.mcplatform.application.economy;

import com.mcplatform.application.economy.port.EconomyReadStore;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.EconomyEventType;
import java.util.Optional;

/**
 * Read-only use case (spec 007, US2): a keyset-paginated page of the SERVER-WIDE economy history (no
 * player filter), newest-first, with optional {@code currency}/{@code type}/{@code source} filters. No
 * mutation, no events. The page-size policy (default/max/non-positive→400) is reused 1:1 from
 * {@link EconomyHistoryService#clampLimit(Integer)} so the player- and server-wide paths never drift.
 */
public final class ServerHistoryQuery {

    private final EconomyReadStore store;

    public ServerHistoryQuery(EconomyReadStore store) {
        this.store = store;
    }

    public EconomyHistoryPage history(Optional<CurrencyCode> currency, Optional<EconomyEventType> eventType,
            Optional<String> source, Long cursorBeforeSeqNo, Integer limit) {
        return store.findServerHistory(currency, eventType, source, cursorBeforeSeqNo,
                EconomyHistoryService.clampLimit(limit));
    }
}
