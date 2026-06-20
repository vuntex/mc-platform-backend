package com.mcplatform.protocol.economy;

import java.util.UUID;

/**
 * Request body for a transfer. {@code to} is the receiving player. {@code correlationId} is optional —
 * pass a stable id to make the transfer idempotent across retries, omit it (null) to have the server
 * generate one. {@code source} is optional (null/blank → server default). Pure data (JDK only); the
 * backend maps these fields to domain types.
 */
public record TransferRequest(UUID to, long amount, UUID correlationId, String source) {
}
