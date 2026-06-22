package com.mcplatform.domain.report;

import java.util.List;

/**
 * Immutable snapshot of the public chat window the plugin attached to a report (a conversational window
 * that may include several senders). Optional — may be empty. Validates size/length bounds so a
 * malicious client cannot store an unbounded blob.
 */
public record ChatContext(List<ChatContextEntry> entries) {

    public static final int MAX_ENTRIES = 30;
    public static final int MAX_TEXT_LENGTH = 256;
    public static final ChatContext EMPTY = new ChatContext(List.of());

    public ChatContext {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) {
            throw new ReportValidationException("chat context exceeds " + MAX_ENTRIES + " entries");
        }
        for (ChatContextEntry e : entries) {
            if (e.text().length() > MAX_TEXT_LENGTH) {
                throw new ReportValidationException("chat message exceeds " + MAX_TEXT_LENGTH + " chars");
            }
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }
}
