package com.mcplatform.protocol.report;

import java.util.UUID;

/**
 * One public chat line in a report's chat-context snapshot — shared REST contract (JDK only). The plugin
 * captures these in a local ring buffer; the backend stores the snapshot verbatim. {@code sender} may be
 * any player who was chatting (a conversational window, not only the reported target).
 */
public record ChatMessage(UUID sender, String text, long timestampEpochMilli) {
}
