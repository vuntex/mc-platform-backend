package com.mcplatform.protocol.economy;

import java.util.List;
import java.util.UUID;

/**
 * Response body for a page of a player's economy history — the shared REST contract between backend
 * and plugin/web. Pure data (JDK only); JSON (de)serialization happens in the backend/plugin, never
 * here. Field names are the wire contract.
 *
 * <p>Entries are newest-first (descending {@code sequenceNo}). {@code nextCursor} is the
 * {@code sequenceNo} to pass as the {@code before} query param to fetch the next (older) page, or
 * {@code null} when this is the last page.
 */
public record EconomyHistoryResponse(UUID player, List<EconomyEventEntry> entries, Long nextCursor) {
}
