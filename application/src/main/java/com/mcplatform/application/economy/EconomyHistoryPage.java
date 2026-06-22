package com.mcplatform.application.economy;

import java.util.List;
import java.util.Objects;

/**
 * A keyset-paginated page of economy history, newest-first. {@code nextCursor} is the
 * {@code sequenceNo} to pass as the cursor for the next (older) page, or {@code null} when this is the
 * last page.
 */
public record EconomyHistoryPage(List<EconomyHistoryEntry> entries, Long nextCursor) {

    public EconomyHistoryPage {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }
}
