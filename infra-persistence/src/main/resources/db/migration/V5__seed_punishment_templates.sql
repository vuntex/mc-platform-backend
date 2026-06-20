-- Starter templates. Reasons/durations/permissions are config and editable via the web interface.
-- Idempotent so re-runs are safe.
INSERT INTO punishment_template (key, type, default_reason, duration_millis, required_permission, active) VALUES
    ('cheating',   'TEMPBAN', 'Cheating',          604800000, 'punishment.cheating', true),  -- 7 days
    ('spam',       'CHATBAN', 'Chat spam',           3600000, 'punishment.spam',     true),  -- 1 hour
    ('warn_minor', 'WARN',    'Minor rule break',         NULL, 'punishment.warn',    true)
ON CONFLICT (key) DO NOTHING;
