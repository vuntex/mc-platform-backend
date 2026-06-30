package com.mcplatform.application.economy.port;

/** Whether a transaction is a single event (CREDIT/DEBIT/SET) or a two-leg transfer (spec 007, US3). */
public enum TransactionKind {
    SINGLE,
    TRANSFER
}
