-- Report team permissions. Creating a report is open to all players (not gated). Viewing the open list
-- and changing status are backend-authoritative: granted to MODERATOR here; ADMIN already holds '*'.
-- Idempotent.
INSERT INTO team_role_permission (role, permission) VALUES
    ('MODERATOR', 'report.view'),
    ('MODERATOR', 'report.handle')
ON CONFLICT (role, permission) DO NOTHING;
