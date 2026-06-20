package com.mcplatform.application.economy;

import com.mcplatform.domain.economy.Balance;

/** Result of a transfer use case: the resulting balances of both parties. */
public record TransferOutcome(Balance from, Balance to) {
}
