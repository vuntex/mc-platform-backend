-- Reports — moderation module (accusation, NOT a verdict; Constitution principle 16). Deliberately
-- state-stored (current-state row + status-history audit), NOT event-sourced like economy/punishment:
-- a report is not money-/verdict-critical, and the only audit need ("who changed the status when") is
-- covered by report_status_history. Idempotency/dedupe is a STATIC invariant (status value), so unlike
-- punishment it is expressed directly by a partial unique index.

-- report — current state per aggregate
CREATE TABLE report (
    report_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_uuid         UUID NOT NULL REFERENCES player(uuid),
    target_uuid           UUID NOT NULL REFERENCES player(uuid),
    category              VARCHAR(32)  NOT NULL,            -- ReportCategory
    detail                VARCHAR(256) NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- ReportStatus (OPEN|IN_PROGRESS|RESOLVED|REJECTED)
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_handled_by       UUID REFERENCES player(uuid),     -- null until first status change
    last_status_change_at TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,  -- optimistic lock for status changes
    CONSTRAINT chk_report_not_self CHECK (reporter_uuid <> target_uuid)
);
-- Dedupe/idempotency (principle 7): at most ONE open report per (reporter, target).
CREATE UNIQUE INDEX uq_report_open ON report (reporter_uuid, target_uuid)
    WHERE status IN ('OPEN', 'IN_PROGRESS');
-- Team "open list" lookup (oldest first).
CREATE INDEX idx_report_open ON report (status, created_at) WHERE status IN ('OPEN', 'IN_PROGRESS');
-- Cooldown lookup: most recent report by a reporter.
CREATE INDEX idx_report_reporter_created ON report (reporter_uuid, created_at DESC);

-- report_chat_message — immutable public-chat snapshot attached at create time (1:N child of report).
-- Stored as rows (not JSONB) so the jOOQ-only persistence module needs no JSON library. sender_uuid is
-- snapshot data (a player who was chatting) and is intentionally NOT a foreign key.
CREATE TABLE report_chat_message (
    id          BIGSERIAL PRIMARY KEY,
    report_id   UUID NOT NULL REFERENCES report(report_id) ON DELETE CASCADE,
    ordinal     INT  NOT NULL,                 -- preserves snapshot order
    sender_uuid UUID NOT NULL,
    text        VARCHAR(256) NOT NULL,
    sent_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_report_chat_report ON report_chat_message (report_id, ordinal);

-- report_status_history — audit trail: one row per status change (who + when), analogous to config_audit.
CREATE TABLE report_status_history (
    id         BIGSERIAL PRIMARY KEY,
    report_id  UUID NOT NULL REFERENCES report(report_id),
    old_status VARCHAR(16),                    -- null on the initial (creation) row
    new_status VARCHAR(16) NOT NULL,
    changed_by UUID NOT NULL REFERENCES player(uuid),  -- reporter on create, team member on transitions
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_history_report ON report_status_history (report_id, changed_at);
