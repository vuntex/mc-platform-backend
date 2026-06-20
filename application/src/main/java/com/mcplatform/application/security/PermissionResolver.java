package com.mcplatform.application.security;

import java.util.UUID;

/**
 * Backend-authoritative permission check. Given the executing team member and a permission string,
 * answers whether the action is allowed. The plugin/web never decides this — the backend does.
 *
 * <p>Outbound port: the first implementation reads a team-role table; a later LuckPerms-backed
 * implementation replaces ONLY that one class — nothing that depends on this interface changes.
 */
public interface PermissionResolver {

    boolean hasPermission(UUID staffUuid, String permission);
}
