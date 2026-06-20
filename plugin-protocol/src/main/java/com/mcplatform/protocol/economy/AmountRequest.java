package com.mcplatform.protocol.economy;

import java.util.UUID;

/**
 * Request body for credit/debit/set. {@code transactionId} is optional — pass a stable id to make the
 * operation idempotent across retries, omit it (null) to have the server generate one. {@code source}
 * is optional too (null/blank → the server applies an endpoint-specific default). Pure data (JDK
 * only); the backend maps these fields to domain types.
 */
public record AmountRequest(long amount, UUID transactionId, String source) {
}
