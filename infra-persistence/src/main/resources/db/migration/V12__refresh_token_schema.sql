-- JWT-Login-Session (state-stored). Eine Tabelle: das rotierende Refresh-Token. Das Access-Token ist
-- stateless (HS256) und wird NICHT gespeichert. Am Wert wird NUR der SHA-256-Hash abgelegt (research R3).
-- Rotation markiert das alte Token als konsumiert (rotated_at) statt es zu löschen, damit ein Replay
-- vom "unbekannt/abgelaufen"-Fall unterscheidbar bleibt (Diebstahls-Signal). Invalidierung läuft über
-- player_uuid (kein Familien-Konzept im Schema); rotated_from ist nur ein Lineage-Breadcrumb (kein FK).
CREATE TABLE refresh_token (
    token_hash    VARCHAR(64) PRIMARY KEY,                 -- SHA-256 hex des Rohtokens, nie Klartext
    player_uuid   UUID NOT NULL REFERENCES player(uuid),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    rotated_at    TIMESTAMPTZ,                             -- NULL = aktiv; gesetzt = rotiert/konsumiert
    rotated_from  VARCHAR(64)                              -- Vorgänger-token_hash (Lineage; kein FK)
);
CREATE INDEX idx_refresh_token_player ON refresh_token (player_uuid);   -- all-player-Invalidierung + Listing
CREATE INDEX idx_refresh_token_expires ON refresh_token (expires_at);   -- Purge-Hygiene
