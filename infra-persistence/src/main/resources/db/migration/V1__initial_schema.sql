-- MC Platform — initial schema (PROGRESS.md section 6).
-- Money is always BIGINT, never float. Economy is event-sourced.

-- player — Stammdaten (state-stored)
CREATE TABLE player (
    uuid             UUID PRIMARY KEY,
    name             VARCHAR(16) NOT NULL,         -- Cache, kann veralten
    name_updated_at  TIMESTAMPTZ NOT NULL,
    first_seen       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_player_name_lower ON player (LOWER(name));

-- currency — Währungen als Config
CREATE TABLE currency (
    code            VARCHAR(32) PRIMARY KEY,       -- 'COINS', 'GEMS'
    display_name    VARCHAR(64) NOT NULL,
    symbol          VARCHAR(8),
    decimal_places  SMALLINT NOT NULL DEFAULT 0,   -- 0 = ganze Coins
    default_balance BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- economy_event — Event Store (append-only)
CREATE TABLE economy_event (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no     BIGSERIAL NOT NULL,            -- globale Ordnung
    player_uuid     UUID NOT NULL REFERENCES player(uuid),
    currency_code   VARCHAR(32) NOT NULL REFERENCES currency(code),
    event_type      VARCHAR(32) NOT NULL,          -- CREDITED|DEBITED|SET|TRANSFER_OUT|TRANSFER_IN
    amount          BIGINT NOT NULL,               -- immer positiv; Richtung über event_type
    balance_after   BIGINT NOT NULL,               -- Stand nach diesem Event
    transaction_id  UUID NOT NULL,                 -- Idempotenz-Schlüssel
    source          VARCHAR(64) NOT NULL,          -- 'WEB','PLUGIN:shop','SYSTEM:mobkill'
    metadata        JSONB,                         -- frei: shop_id, reason, correlation_id...
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transaction UNIQUE (transaction_id)
);
CREATE INDEX idx_event_player_currency ON economy_event (player_uuid, currency_code, sequence_no);
CREATE INDEX idx_event_created ON economy_event (created_at);

-- player_balance — Projektion (Snapshot)
CREATE TABLE player_balance (
    player_uuid    UUID NOT NULL REFERENCES player(uuid),
    currency_code  VARCHAR(32) NOT NULL REFERENCES currency(code),
    balance        BIGINT NOT NULL DEFAULT 0,
    version        BIGINT NOT NULL DEFAULT 0,      -- sequence_no des letzten Events
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_uuid, currency_code)
);

-- server_config — Konfiguration übers Webinterface
CREATE TABLE server_config (
    config_key   VARCHAR(128) PRIMARY KEY,         -- 'economy.starting_coins'
    value        JSONB NOT NULL,                   -- typflexibel
    value_type   VARCHAR(16) NOT NULL,             -- INT|STRING|BOOL|LIST|OBJECT
    scope        VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',  -- GLOBAL|SERVER:lobby...
    description  TEXT,
    updated_by   VARCHAR(64),
    version      BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE config_audit (
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(128) NOT NULL,
    old_value    JSONB,
    new_value    JSONB,
    changed_by   VARCHAR(64) NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
