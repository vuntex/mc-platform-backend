package com.mcplatform.domain.report;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.Objects;

/**
 * One public chat line in a report's chat-context snapshot: who sent it, the text, and when. Snapshot
 * data — {@code sender} may be any player who was chatting (not necessarily the reported target).
 */
public record ChatContextEntry(PlayerId sender, String text, Instant at) {

    public ChatContextEntry {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(at, "at");
    }
}
