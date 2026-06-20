package com.mcplatform.application.economy.port;

/** Outcome of an atomic transfer: the append result of each leg (out = sender, in = receiver). */
public record TransferResult(AppendResult out, AppendResult in) {
}
