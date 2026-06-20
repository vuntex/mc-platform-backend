package com.mcplatform.application.economy.port;

import java.util.List;

/**
 * Outbound port for currency configuration (implemented by infra-persistence). The starting balance
 * lives in {@code currency.default_balance} and is editable via the web interface without a code
 * change — never hard-code it.
 */
public interface CurrencyRepository {

    /** All configured currencies with their starting balance. */
    List<CurrencyDefault> findAll();
}
