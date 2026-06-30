-- Server-wide economy history (spec 007, US2) sorts/keyset-paginates on sequence_no DESC without a
-- player prefix; idx_event_player_currency (leading player_uuid) does not serve that, and sequence_no
-- (BIGSERIAL) has no standalone index. Player-filtered history keeps using idx_event_player_currency.
-- No source index yet (free-form, low-selectivity) — add only if a measured need appears.
CREATE INDEX idx_event_seq_desc ON economy_event (sequence_no DESC);
