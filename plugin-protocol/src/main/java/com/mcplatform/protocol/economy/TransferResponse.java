package com.mcplatform.protocol.economy;

/**
 * Response body for a transfer: the resulting balances of both legs (sender {@code from} and receiver
 * {@code to}). Pure data (JDK only).
 */
public record TransferResponse(BalanceResponse from, BalanceResponse to) {
}
