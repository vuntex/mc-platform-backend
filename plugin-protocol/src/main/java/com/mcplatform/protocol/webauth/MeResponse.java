package com.mcplatform.protocol.webauth;

import java.util.List;
import java.util.UUID;

/**
 * Identity + effective rights of the currently authenticated web user ({@code GET /api/web/me}). The
 * {@code uuid} comes from the verified access token (subject); {@code name} is the player's cached
 * Minecraft name (may be null if no player row exists yet). {@code permissions} is the caller's resolved
 * permission set — for client-side UX gating (show/hide write buttons) ONLY; it may contain wildcards
 * ({@code *}, {@code feature.*}), so the client must apply the same match rule the backend uses (global
 * {@code *}, prefix {@code feature.*}, else exact). The real check stays backend-authoritative: a forbidden
 * write still returns 403 (Constitution §12). The list never implies more than the backend would allow.
 * {@code primaryRole} is the caller's display rank (highest-priority active role, default-role fallback) —
 * for badge/name/color in the UI.
 */
public record MeResponse(UUID uuid, String name, List<String> permissions, PrimaryRole primaryRole) {
}
