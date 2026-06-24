-- Web-Auth-Bridge (state-stored, kein Event-Sourcing). Drei Tabellen, eine Migration.
-- Kein Redis/Pub-Sub für dieses Feature; Token liegt hier in der DB.

-- Web-Account: genau einer je Spieler-Identität (UUID-Anker). Kein email/username; nur Passwort-Hash.
CREATE TABLE web_account (
    player_uuid          UUID PRIMARY KEY REFERENCES player(uuid),
    password_hash        VARCHAR(100) NOT NULL,            -- BCrypt (~60 Zeichen), Reserve
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    password_updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Kurzlebiger, einmal verwendbarer Token. Am Wert wird NUR der SHA-256-Hash gespeichert (nie roh).
-- Höchstens ein aktiver Token je (player_uuid, purpose) — DELETE-vor-INSERT erzwingt das zur Laufzeit,
-- der Unique-Constraint sichert die Invariante zusätzlich ab.
CREATE TABLE web_link_token (
    token_hash    VARCHAR(64) PRIMARY KEY,                 -- SHA-256 hex des Rohtokens
    player_uuid   UUID NOT NULL REFERENCES player(uuid),
    purpose       VARCHAR(16) NOT NULL,                    -- LINK | RESET
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_web_link_token_uuid_purpose UNIQUE (player_uuid, purpose)
);
CREATE INDEX idx_web_link_token_expires ON web_link_token (expires_at);

-- Append-only Audit der sicherheitsrelevanten Lebenszyklus-Ereignisse (config_audit-Stil).
-- NIE Klartext-Passwort oder Hash.
CREATE TABLE web_auth_audit (
    id            BIGSERIAL PRIMARY KEY,
    player_uuid   UUID NOT NULL,
    event_type    VARCHAR(32) NOT NULL,                    -- ACCOUNT_CREATED | PASSWORD_RESET
    at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_web_auth_audit_player ON web_auth_audit (player_uuid, at);
