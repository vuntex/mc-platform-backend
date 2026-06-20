-- Start bonus: new players should join with 100 COINS. The starting amount is configuration, not
-- code -- it lives in currency.default_balance and is read by the session-join service. Changing it
-- here (and later via the web interface) needs no code change.
--
-- This only changes the DEFAULT for FUTURE joins. It touches the currency table only, never
-- player_balance, so existing players' balances are not affected retroactively.
UPDATE currency SET default_balance = 100 WHERE code = 'COINS';
