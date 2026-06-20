package com.mcplatform.application.economy.port;

import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.player.PlayerId;
import java.util.Optional;

/** Outbound port for the hot balance cache (implemented by infra-cache). */
public interface BalanceCachePort {

    Optional<Balance> find(PlayerId player, CurrencyCode currency);

    void update(Balance balance);

    void evict(PlayerId player, CurrencyCode currency);
}
