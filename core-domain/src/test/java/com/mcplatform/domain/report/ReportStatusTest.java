package com.mcplatform.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportStatusTest {

    @Test
    void allowedTransitions() {
        assertThat(ReportStatus.OPEN.canTransitionTo(ReportStatus.IN_PROGRESS)).isTrue();
        assertThat(ReportStatus.OPEN.canTransitionTo(ReportStatus.REJECTED)).isTrue();
        assertThat(ReportStatus.IN_PROGRESS.canTransitionTo(ReportStatus.RESOLVED)).isTrue();
        assertThat(ReportStatus.IN_PROGRESS.canTransitionTo(ReportStatus.REJECTED)).isTrue();
    }

    @Test
    void forbiddenTransitions() {
        assertThat(ReportStatus.OPEN.canTransitionTo(ReportStatus.RESOLVED)).isFalse();
        assertThat(ReportStatus.OPEN.canTransitionTo(ReportStatus.OPEN)).isFalse();
        assertThat(ReportStatus.IN_PROGRESS.canTransitionTo(ReportStatus.OPEN)).isFalse();
        assertThat(ReportStatus.RESOLVED.canTransitionTo(ReportStatus.OPEN)).isFalse();
        assertThat(ReportStatus.RESOLVED.canTransitionTo(ReportStatus.IN_PROGRESS)).isFalse();
        assertThat(ReportStatus.REJECTED.canTransitionTo(ReportStatus.OPEN)).isFalse();
    }

    @Test
    void openAndTerminalClassification() {
        assertThat(ReportStatus.OPEN.isOpen()).isTrue();
        assertThat(ReportStatus.IN_PROGRESS.isOpen()).isTrue();
        assertThat(ReportStatus.RESOLVED.isOpen()).isFalse();
        assertThat(ReportStatus.RESOLVED.isTerminal()).isTrue();
        assertThat(ReportStatus.REJECTED.isTerminal()).isTrue();
    }

    @Test
    void aggregateTransitionEnforcesTheMatrixAndRecordsHandler() {
        PlayerId reporter = PlayerId.of(UUID.randomUUID());
        PlayerId target = PlayerId.of(UUID.randomUUID());
        PlayerId handler = PlayerId.of(UUID.randomUUID());
        Instant now = Instant.parse("2026-06-22T12:00:00Z");
        Report open = Report.create(ReportId.random(), reporter, target, ReportCategory.CHEATING, "x",
                ChatContext.EMPTY, now);

        Report inProgress = open.transitionTo(ReportStatus.IN_PROGRESS, handler, now);
        assertThat(inProgress.status()).isEqualTo(ReportStatus.IN_PROGRESS);
        assertThat(inProgress.lastHandledBy()).isEqualTo(handler);
        assertThat(inProgress.lastStatusChangeAt()).isEqualTo(now);

        assertThatThrownBy(() -> open.transitionTo(ReportStatus.RESOLVED, handler, now))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }
}
