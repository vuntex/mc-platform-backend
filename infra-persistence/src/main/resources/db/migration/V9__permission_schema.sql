-- Permission/Rank system — fourth feature. State-stored (current-state rows + a separate grant_audit
-- trail), NOT event-sourced: permissions are config-like, not money-/verdict-critical (Constitution VI).
-- Replaces the minimal team_role_member/team_role_permission backing of the PermissionResolver: the old
-- member table is empty (no seeded members), so there is no player backfill — but the seeded
-- ADMIN/MODERATOR permissions are migrated into the new model before the old tables are dropped.
-- The PermissionResolver port signature is unchanged; only its jOOQ implementation grows richer.

-- role — flat role master data (the old rank/rank_data/rank_server_data triple merged; no server scope,
-- no inheritance). weight/team_rank affect ONLY display selection, never resolution (FR-010).
CREATE TABLE role (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(32)  NOT NULL,
    display_name    VARCHAR(64)  NOT NULL,
    color           VARCHAR(32),
    prefix          VARCHAR(100),
    suffix          VARCHAR(100),
    tab_list_color  VARCHAR(32),
    tab_list_icon   VARCHAR(50),
    weight          INT          NOT NULL DEFAULT 0,
    team_rank       BOOLEAN      NOT NULL DEFAULT false,
    active          BOOLEAN      NOT NULL DEFAULT true,
    is_default      BOOLEAN      NOT NULL DEFAULT false,
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Case-insensitive unique role name (FR-008).
CREATE UNIQUE INDEX uq_role_name_ci ON role (lower(name));
-- At most one default role (FR-012).
CREATE UNIQUE INDEX uq_role_single_default ON role (is_default) WHERE is_default;

-- role_permission — role-permission configuration (master data, no expiry). Wildcards allowed. Audit
-- columns added_by/added_at (FR-011). Cascades when its role is deleted (FR-012a).
CREATE TABLE role_permission (
    role_id     BIGINT       NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission  VARCHAR(128) NOT NULL,
    added_by    UUID,
    added_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission)
);

-- player_role_grant — rank grant (player -> role); at most one row per (uuid, role_id), re-grant is an
-- upsert (FR-014a). Soft 'active' flag (R5); resolution also filters expires_at against now(). Deleting
-- the role cascades its grant rows away (their end-of-life is captured in grant_audit, FR-012a).
CREATE TABLE player_role_grant (
    uuid        UUID         NOT NULL,
    role_id     BIGINT       NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    issued_by   UUID         NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,                       -- null = permanent
    reason      VARCHAR(256),                      -- optional
    active      BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (uuid, role_id)
);
CREATE INDEX idx_prg_role   ON player_role_grant (role_id) WHERE active;
CREATE INDEX idx_prg_expiry ON player_role_grant (expires_at) WHERE active AND expires_at IS NOT NULL;

-- player_permission_grant — direct permission grant (player -> single permission), same lifecycle.
CREATE TABLE player_permission_grant (
    uuid        UUID         NOT NULL,
    permission  VARCHAR(128) NOT NULL,
    issued_by   UUID         NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,
    reason      VARCHAR(256),
    active      BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (uuid, permission)
);
CREATE INDEX idx_ppg_expiry ON player_permission_grant (expires_at) WHERE active AND expires_at IS NOT NULL;

-- grant_audit — append-only GRANT/REVOKE/EXPIRE trail (analogous to config_audit). No FK to role, so the
-- history survives role deletion. actor_uuid is the staff UUID or the SYSTEM sentinel for EXPIRE (FR-016a).
CREATE TABLE grant_audit (
    id           BIGSERIAL    PRIMARY KEY,
    action       VARCHAR(16)  NOT NULL,            -- GRANT | REVOKE | EXPIRE
    grant_type   VARCHAR(16)  NOT NULL,            -- ROLE | PERMISSION
    player_uuid  UUID         NOT NULL,
    role_id      BIGINT,                           -- set when grant_type = ROLE
    permission   VARCHAR(128),                     -- set when grant_type = PERMISSION
    actor_uuid   UUID         NOT NULL,
    reason       VARCHAR(256),
    at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_grant_audit_player ON grant_audit (player_uuid, at);

-- Seed roles: DEFAULT (empty permission set, FR-012), MODERATOR + ADMIN (team ranks).
INSERT INTO role (name, display_name, weight, team_rank, active, is_default) VALUES
    ('DEFAULT',   'Default',   0,   false, true, true),
    ('MODERATOR', 'Moderator', 50,  true,  true, false),
    ('ADMIN',     'Admin',     100, true,  true, false);

-- Migrate the previously seeded team_role_permission rows into role_permission (preserve consumer
-- permissions: ADMIN '*'; MODERATOR punishment subset + report perms). Idempotent.
INSERT INTO role_permission (role_id, permission)
    SELECT id, '*' FROM role WHERE name = 'ADMIN'
    ON CONFLICT DO NOTHING;
INSERT INTO role_permission (role_id, permission)
    SELECT r.id, p.perm
    FROM role r
    CROSS JOIN (VALUES
        ('punishment.spam'), ('punishment.warn'), ('punishment.revoke'),
        ('punishment.issue.warn'), ('punishment.issue.chatban'),
        ('report.view'), ('report.handle')) AS p(perm)
    WHERE r.name = 'MODERATOR'
    ON CONFLICT DO NOTHING;

-- Replace (ablösen) the minimal backing tables. team_role_member is empty -> no data loss.
DROP TABLE team_role_member;
DROP TABLE team_role_permission;
