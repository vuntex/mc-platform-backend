-- role_audit — append-only trail of role master-data + role-permission configuration changes
-- (FR-025a), analogous to config_audit / grant_audit. No FK to role, so the trail survives a deleted
-- role (same rationale as grant_audit). Player grants keep their own grant_audit trail.
CREATE TABLE role_audit (
    id          BIGSERIAL    PRIMARY KEY,
    action      VARCHAR(32)  NOT NULL,   -- ROLE_CREATE|ROLE_UPDATE|ROLE_DELETE|ROLE_PERMISSION_ADD|ROLE_PERMISSION_REMOVE
    role_id     BIGINT       NOT NULL,   -- no FK (survives deletion)
    role_name   VARCHAR(32),             -- name snapshot (esp. useful for ROLE_DELETE)
    permission  VARCHAR(128),            -- set only for ROLE_PERMISSION_ADD/REMOVE
    actor       UUID         NOT NULL,   -- acting admin (= JWT uuid); never from the request body
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_role_audit_role ON role_audit (role_id, at);
