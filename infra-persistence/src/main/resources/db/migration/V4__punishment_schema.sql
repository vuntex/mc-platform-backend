-- Punishments — second event-sourced feature, modelled as a parallel sibling of economy (Prompt A).
-- Like economy: append-only event store + a projection, idempotency via a UNIQUE transaction_id.
-- Unlike economy: no running scalar to optimistic-lock; the only concurrency invariant is "at most
-- one active punishment per exclusive category per player", enforced under a per-player row lock at
-- write time (a static partial unique index cannot express time-based expiry).

-- punishment_event — Event Store (append-only)
CREATE TABLE punishment_event (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no     BIGSERIAL NOT NULL,            -- globale Ordnung
    punishment_id   UUID NOT NULL,                 -- aggregate id
    player_uuid     UUID NOT NULL REFERENCES player(uuid),
    event_type      VARCHAR(16) NOT NULL,          -- ISSUED|REVOKED
    punishment_type VARCHAR(16),                   -- WARN|CHATBAN|TEMPBAN|PERMABAN (set on ISSUED)
    reason          TEXT NOT NULL,
    actor_uuid      UUID NOT NULL,                 -- issuedBy / revokedBy
    expires_at      TIMESTAMPTZ,                   -- set on ISSUED for time-bound types
    transaction_id  UUID NOT NULL,                 -- Idempotenz-Schlüssel
    source          VARCHAR(64) NOT NULL,          -- 'WEB','PLUGIN','SYSTEM:automod'
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_punishment_transaction UNIQUE (transaction_id)
);
CREATE INDEX idx_punishment_event_punishment ON punishment_event (punishment_id, sequence_no);
CREATE INDEX idx_punishment_event_player ON punishment_event (player_uuid, sequence_no);

-- punishment — Projektion (current state per aggregate)
CREATE TABLE punishment (
    punishment_id  UUID PRIMARY KEY,
    player_uuid    UUID NOT NULL REFERENCES player(uuid),
    type           VARCHAR(16) NOT NULL,           -- WARN|CHATBAN|TEMPBAN|PERMABAN
    category       VARCHAR(16) NOT NULL,           -- NOTICE|CHAT|ACCESS (derived; stored for the active check)
    reason         TEXT NOT NULL,
    issued_by      UUID NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ,                    -- null = permanent / not applicable
    revoked_by     UUID,
    revoked_at     TIMESTAMPTZ,
    version        BIGINT NOT NULL,                -- sequence_no des letzten Events
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- "Active" lookups (issued, not revoked, not expired) start from the un-revoked rows of a player.
CREATE INDEX idx_punishment_player_active ON punishment (player_uuid, category) WHERE revoked_at IS NULL;

-- team roles — first PermissionResolver backing: uuid -> role, role -> permissions.
-- A '*' permission grants everything (admin). A later LuckPerms resolver replaces only the resolver
-- class, not this schema's role of being the simple default.
CREATE TABLE team_role_member (
    uuid       UUID PRIMARY KEY,
    role       VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE team_role_permission (
    role       VARCHAR(32) NOT NULL,
    permission VARCHAR(128) NOT NULL,
    PRIMARY KEY (role, permission)
);

-- punishment_template — Config (web-interface manageable), with audit analogous to server_config.
CREATE TABLE punishment_template (
    key                 VARCHAR(64) PRIMARY KEY,
    type                VARCHAR(16) NOT NULL,
    default_reason      TEXT NOT NULL,
    duration_millis     BIGINT,                    -- null for WARN/PERMABAN
    required_permission VARCHAR(128) NOT NULL,     -- gate: only holders may apply this template
    active              BOOLEAN NOT NULL DEFAULT true,
    version             BIGINT NOT NULL DEFAULT 0,
    updated_by          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE punishment_template_audit (
    id           BIGSERIAL PRIMARY KEY,
    template_key VARCHAR(64) NOT NULL,
    old_value    JSONB,
    new_value    JSONB,
    changed_by   VARCHAR(64) NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
