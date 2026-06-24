-- Additive: optional display icon on the flat role table, so staff can visually distinguish ranks in
-- the later in-game grant menu (old UX: icon_id on RankData — here new and lean, a single column next
-- to prefix/color, NOT a separate icon relationship). The backend stores an OPAQUE prefixed string
-- ('<type>:<payload>', e.g. material:DIAMOND_SWORD / head-texture:<texture> / head-player:<uuid>) and
-- NEVER interprets it — only the plugin does. New icon types need no schema change (the column holds
-- any string); only the plugin (and the backend's coarse prefix allowlist) learns new prefixes.
ALTER TABLE role ADD COLUMN display_icon VARCHAR(512);
