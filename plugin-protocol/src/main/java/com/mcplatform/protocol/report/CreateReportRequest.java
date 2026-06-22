package com.mcplatform.protocol.report;

import java.util.List;
import java.util.UUID;

/**
 * Request body to create a report. {@code chatContext} is optional — pass null or an empty list when the
 * report has no chat relevance. Pure data (JDK only); a {@code List} of records needs no JSON library here.
 */
public record CreateReportRequest(
        UUID reporter,
        UUID target,
        String category,
        String detail,
        List<ChatMessage> chatContext) {
}
