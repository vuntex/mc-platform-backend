-- Default currency. decimal_places = 0 -> whole coins. Idempotent so re-runs are safe.
INSERT INTO currency (code, display_name, symbol, decimal_places, default_balance)
VALUES ('COINS', 'Coins', NULL, 0, 0)
ON CONFLICT (code) DO NOTHING;
