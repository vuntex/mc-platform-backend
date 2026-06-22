package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.REPORT;
import static com.mcplatform.persistence.jooq.Tables.REPORT_CHAT_MESSAGE;
import static com.mcplatform.persistence.jooq.Tables.REPORT_STATUS_HISTORY;

import com.mcplatform.application.report.port.ReportConflictException;
import com.mcplatform.application.report.port.ReportRepository;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.report.ChatContext;
import com.mcplatform.domain.report.ChatContextEntry;
import com.mcplatform.domain.report.Report;
import com.mcplatform.domain.report.ReportCategory;
import com.mcplatform.domain.report.ReportId;
import com.mcplatform.domain.report.ReportStatus;
import com.mcplatform.domain.report.ReportValidationException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;

/**
 * jOOQ adapter over the state-stored report tables. A create writes the report row, the immutable chat
 * snapshot rows and the initial status-history row in one transaction. The open-report uniqueness
 * (partial unique index {@code uq_report_open}) is the dedupe/idempotency guard: a conflicting insert is
 * caught and the existing open report is returned. No Spring — jOOQ drives the transaction.
 */
public final class JooqReportRepository implements ReportRepository {

    private static final Set<String> OPEN_STATUSES = Set.of(ReportStatus.OPEN.name(), ReportStatus.IN_PROGRESS.name());

    private final DSLContext dsl;

    public JooqReportRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Report create(Report report) {
        try {
            return dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();
                tx.insertInto(REPORT)
                        .set(REPORT.REPORT_ID, report.id().value())
                        .set(REPORT.REPORTER_UUID, report.reporter().value())
                        .set(REPORT.TARGET_UUID, report.target().value())
                        .set(REPORT.CATEGORY, report.category().name())
                        .set(REPORT.DETAIL, report.detail())
                        .set(REPORT.STATUS, report.status().name())
                        .set(REPORT.CREATED_AT, offset(report.createdAt()))
                        .set(REPORT.VERSION, report.version())
                        .execute();
                insertChat(tx, report.id(), report.chatContext());
                insertHistory(tx, report.id(), null, report.status().name(),
                        report.reporter().value(), offset(report.createdAt()));
                return report;
            });
        } catch (IntegrityConstraintViolationException e) {
            // Either the open-report uniqueness lost a race (→ return the existing open report, dedupe)
            // or a player FK is missing (unknown reporter/target → validation error).
            Optional<Report> existing = findOpenFor(report.reporter(), report.target());
            if (existing.isPresent()) {
                return existing.get();
            }
            throw new ReportValidationException("unknown reporter or target player");
        }
    }

    @Override
    public Optional<Report> findOpenFor(PlayerId reporter, PlayerId target) {
        Record rec = dsl.selectFrom(REPORT)
                .where(REPORT.REPORTER_UUID.eq(reporter.value())
                        .and(REPORT.TARGET_UUID.eq(target.value()))
                        .and(REPORT.STATUS.in(OPEN_STATUSES)))
                .fetchOne();
        return rec == null ? Optional.empty() : Optional.of(toReport(dsl, rec));
    }

    @Override
    public Optional<Instant> lastCreatedAtByReporter(PlayerId reporter) {
        OffsetDateTime last = dsl.select(org.jooq.impl.DSL.max(REPORT.CREATED_AT))
                .from(REPORT)
                .where(REPORT.REPORTER_UUID.eq(reporter.value()))
                .fetchOneInto(OffsetDateTime.class);
        return last == null ? Optional.empty() : Optional.of(last.toInstant());
    }

    @Override
    public List<Report> findOpen() {
        return dsl.selectFrom(REPORT)
                .where(REPORT.STATUS.in(OPEN_STATUSES))
                .orderBy(REPORT.CREATED_AT.asc())
                .fetch(r -> toReport(dsl, r));
    }

    @Override
    public Optional<Report> find(ReportId id) {
        Record rec = dsl.selectFrom(REPORT).where(REPORT.REPORT_ID.eq(id.value())).fetchOne();
        return rec == null ? Optional.empty() : Optional.of(toReport(dsl, rec));
    }

    @Override
    public Report changeStatus(Report transitioned, ReportStatus previousStatus, long expectedVersion) {
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            long newVersion = expectedVersion + 1;
            int updated = tx.update(REPORT)
                    .set(REPORT.STATUS, transitioned.status().name())
                    .set(REPORT.LAST_HANDLED_BY, transitioned.lastHandledBy().value())
                    .set(REPORT.LAST_STATUS_CHANGE_AT, offset(transitioned.lastStatusChangeAt()))
                    .set(REPORT.VERSION, newVersion)
                    .where(REPORT.REPORT_ID.eq(transitioned.id().value())
                            .and(REPORT.VERSION.eq(expectedVersion)))
                    .execute();
            if (updated == 0) {
                throw new ReportConflictException("report was concurrently modified: " + transitioned.id().value());
            }
            insertHistory(tx, transitioned.id(), previousStatus.name(), transitioned.status().name(),
                    transitioned.lastHandledBy().value(), offset(transitioned.lastStatusChangeAt()));
            return new Report(transitioned.id(), transitioned.reporter(), transitioned.target(),
                    transitioned.category(), transitioned.detail(), transitioned.chatContext(),
                    transitioned.status(), transitioned.createdAt(), transitioned.lastHandledBy(),
                    transitioned.lastStatusChangeAt(), newVersion);
        });
    }

    // --- helpers -----------------------------------------------------------

    private void insertChat(DSLContext tx, ReportId reportId, ChatContext chat) {
        List<ChatContextEntry> entries = chat.entries();
        for (int i = 0; i < entries.size(); i++) {
            ChatContextEntry e = entries.get(i);
            tx.insertInto(REPORT_CHAT_MESSAGE)
                    .set(REPORT_CHAT_MESSAGE.REPORT_ID, reportId.value())
                    .set(REPORT_CHAT_MESSAGE.ORDINAL, i)
                    .set(REPORT_CHAT_MESSAGE.SENDER_UUID, e.sender().value())
                    .set(REPORT_CHAT_MESSAGE.TEXT, e.text())
                    .set(REPORT_CHAT_MESSAGE.SENT_AT, offset(e.at()))
                    .execute();
        }
    }

    private void insertHistory(DSLContext tx, ReportId reportId, String oldStatus, String newStatus,
            java.util.UUID changedBy, OffsetDateTime at) {
        tx.insertInto(REPORT_STATUS_HISTORY)
                .set(REPORT_STATUS_HISTORY.REPORT_ID, reportId.value())
                .set(REPORT_STATUS_HISTORY.OLD_STATUS, oldStatus)
                .set(REPORT_STATUS_HISTORY.NEW_STATUS, newStatus)
                .set(REPORT_STATUS_HISTORY.CHANGED_BY, changedBy)
                .set(REPORT_STATUS_HISTORY.CHANGED_AT, at)
                .execute();
    }

    private static Report toReport(DSLContext ctx, Record r) {
        ReportId id = ReportId.of(r.get(REPORT.REPORT_ID));
        ChatContext chat = loadChat(ctx, id);
        java.util.UUID handledBy = r.get(REPORT.LAST_HANDLED_BY);
        OffsetDateTime changeAt = r.get(REPORT.LAST_STATUS_CHANGE_AT);
        return new Report(
                id,
                PlayerId.of(r.get(REPORT.REPORTER_UUID)),
                PlayerId.of(r.get(REPORT.TARGET_UUID)),
                ReportCategory.valueOf(r.get(REPORT.CATEGORY)),
                r.get(REPORT.DETAIL),
                chat,
                ReportStatus.valueOf(r.get(REPORT.STATUS)),
                r.get(REPORT.CREATED_AT).toInstant(),
                handledBy == null ? null : PlayerId.of(handledBy),
                changeAt == null ? null : changeAt.toInstant(),
                r.get(REPORT.VERSION));
    }

    private static ChatContext loadChat(DSLContext ctx, ReportId id) {
        List<ChatContextEntry> entries = ctx.selectFrom(REPORT_CHAT_MESSAGE)
                .where(REPORT_CHAT_MESSAGE.REPORT_ID.eq(id.value()))
                .orderBy(REPORT_CHAT_MESSAGE.ORDINAL.asc())
                .fetch(m -> new ChatContextEntry(
                        PlayerId.of(m.get(REPORT_CHAT_MESSAGE.SENDER_UUID)),
                        m.get(REPORT_CHAT_MESSAGE.TEXT),
                        m.get(REPORT_CHAT_MESSAGE.SENT_AT).toInstant()));
        return entries.isEmpty() ? ChatContext.EMPTY : new ChatContext(entries);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
