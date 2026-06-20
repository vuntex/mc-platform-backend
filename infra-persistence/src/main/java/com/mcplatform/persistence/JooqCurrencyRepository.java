package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.CURRENCY;

import com.mcplatform.application.economy.port.CurrencyDefault;
import com.mcplatform.application.economy.port.CurrencyRepository;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;
import java.util.List;
import org.jooq.DSLContext;

/** jOOQ adapter for currency configuration (state-stored CRUD). */
public final class JooqCurrencyRepository implements CurrencyRepository {

    private final DSLContext dsl;

    public JooqCurrencyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<CurrencyDefault> findAll() {
        return dsl.select(CURRENCY.CODE, CURRENCY.DEFAULT_BALANCE)
                .from(CURRENCY)
                .orderBy(CURRENCY.CODE)
                .fetch(r -> new CurrencyDefault(CurrencyCode.of(r.value1()), Money.of(r.value2())));
    }
}
