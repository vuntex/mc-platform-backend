package com.mcplatform.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReportTest {

    private final PlayerId reporter = PlayerId.of(UUID.randomUUID());
    private final PlayerId target = PlayerId.of(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-06-22T12:00:00Z");

    @Test
    void createBuildsAnOpenReportAndStripsDetail() {
        Report r = Report.create(ReportId.random(), reporter, target, ReportCategory.CHEATING,
                "  fliegt in der mine  ", ChatContext.EMPTY, now);

        assertThat(r.status()).isEqualTo(ReportStatus.OPEN);
        assertThat(r.detail()).isEqualTo("fliegt in der mine");
        assertThat(r.lastHandledBy()).isNull();
        assertThat(r.version()).isZero();
    }

    @Test
    void selfReportIsRejected() {
        assertThatThrownBy(() -> Report.create(ReportId.random(), reporter, reporter, ReportCategory.SONSTIGES,
                "x", ChatContext.EMPTY, now))
                .isInstanceOf(ReportValidationException.class);
    }

    @Test
    void blankDetailIsRejected() {
        assertThatThrownBy(() -> Report.create(ReportId.random(), reporter, target, ReportCategory.SONSTIGES,
                "   ", ChatContext.EMPTY, now))
                .isInstanceOf(ReportValidationException.class);
    }

    @Test
    void tooLongDetailIsRejected() {
        String tooLong = "x".repeat(Report.MAX_DETAIL_LENGTH + 1);
        assertThatThrownBy(() -> Report.create(ReportId.random(), reporter, target, ReportCategory.SPAM_WERBUNG,
                tooLong, ChatContext.EMPTY, now))
                .isInstanceOf(ReportValidationException.class);
    }

    @Test
    void chatContextRejectsTooManyEntries() {
        List<ChatContextEntry> entries = IntStream.range(0, ChatContext.MAX_ENTRIES + 1)
                .mapToObj(i -> new ChatContextEntry(target, "msg " + i, now))
                .toList();
        assertThatThrownBy(() -> new ChatContext(entries)).isInstanceOf(ReportValidationException.class);
    }

    @Test
    void chatContextRejectsOverlongText() {
        ChatContextEntry tooLong = new ChatContextEntry(target, "x".repeat(ChatContext.MAX_TEXT_LENGTH + 1), now);
        assertThatThrownBy(() -> new ChatContext(List.of(tooLong))).isInstanceOf(ReportValidationException.class);
    }
}
