-- DEFAULT is the implicit fallback role, never a real grant — it applies exactly when a player holds no
-- other active role (see JooqPermissionResolver / EffectivePermissions). PermissionAdminService now blocks
-- granting/revoking it, but rows created before that guard existed can still linger and make a player show
-- DEFAULT alongside a real role. Remove any such legacy grants. Idempotent: re-running deletes nothing.
DELETE FROM player_role_grant
WHERE role_id IN (SELECT id FROM role WHERE is_default);
