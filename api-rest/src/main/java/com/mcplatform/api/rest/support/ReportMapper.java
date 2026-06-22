package com.mcplatform.api.rest.support;

import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.report.ChatContext;
import com.mcplatform.domain.report.ChatContextEntry;
import com.mcplatform.domain.report.Report;
import com.mcplatform.domain.report.ReportCategory;
import com.mcplatform.domain.report.ReportStatus;
import com.mcplatform.domain.report.ReportValidationException;
import com.mcplatform.protocol.report.ChatMessage;
import com.mcplatform.protocol.report.ReportResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Maps between the shared dependency-free report protocol DTOs and the domain types. The protocol records
 * stay pure data; all domain coupling for the report endpoints lives here. An unknown category or status
 * is a {@link ReportValidationException} (422), consistent with the report error model.
 */
public final class ReportMapper {

    private ReportMapper() {}

    public static ReportResponse response(Report r) {
        return new ReportResponse(
                r.id().value(),
                r.reporter().value(),
                r.target().value(),
                r.category().name(),
                r.detail(),
                r.status().name(),
                r.createdAt().toEpochMilli(),
                r.lastHandledBy() == null ? null : r.lastHandledBy().value(),
                r.lastStatusChangeAt() == null ? 0L : r.lastStatusChangeAt().toEpochMilli(),
                chatMessages(r.chatContext()),
                r.version());
    }

    /** Parses the wire category to the domain enum; an unknown value is a 422 (semantic). */
    public static ReportCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new ReportValidationException("category is required");
        }
        try {
            return ReportCategory.valueOf(category.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ReportValidationException("unknown report category: " + category);
        }
    }

    /** Parses the wire status to the domain enum; an unknown value is a 422 (semantic). */
    public static ReportStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ReportValidationException("status is required");
        }
        try {
            return ReportStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ReportValidationException("unknown report status: " + status);
        }
    }

    /** Builds the domain chat-context snapshot from the request DTO list (null/empty → empty snapshot). */
    public static ChatContext chatContext(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return ChatContext.EMPTY;
        }
        List<ChatContextEntry> entries = messages.stream()
                .map(m -> new ChatContextEntry(
                        PlayerId.of(m.sender()), m.text(), Instant.ofEpochMilli(m.timestampEpochMilli())))
                .toList();
        return new ChatContext(entries);
    }

    private static List<ChatMessage> chatMessages(ChatContext ctx) {
        return ctx.entries().stream()
                .map(e -> new ChatMessage(e.sender().value(), e.text(), e.at().toEpochMilli()))
                .toList();
    }
}
