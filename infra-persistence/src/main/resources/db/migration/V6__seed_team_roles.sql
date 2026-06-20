-- Role -> permission mappings. ADMIN holds the '*' wildcard (everything); MODERATOR a focused subset
-- (notably NOT 'punishment.cheating', so the cheating template is admin-only). Members (uuid -> role)
-- are runtime data assigned via the web interface, so none are seeded here. Idempotent.
INSERT INTO team_role_permission (role, permission) VALUES
    ('ADMIN',     '*'),
    ('MODERATOR', 'punishment.spam'),
    ('MODERATOR', 'punishment.warn'),
    ('MODERATOR', 'punishment.revoke'),
    ('MODERATOR', 'punishment.issue.warn'),
    ('MODERATOR', 'punishment.issue.chatban')
ON CONFLICT (role, permission) DO NOTHING;
