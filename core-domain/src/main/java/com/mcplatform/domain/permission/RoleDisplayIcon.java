package com.mcplatform.domain.permission;

import java.util.Set;

/**
 * Grob (coarse) validation of a role's optional display icon. The backend stores an OPAQUE prefixed
 * string ({@code <type>:<payload>}) and NEVER interprets the payload — no ItemStack, no Bukkit, no
 * NBT/Base64 blob; the icon is interpreted exclusively by the plugin (treated like {@code prefix}/
 * {@code color}: stored, published, passed through).
 *
 * <p>Validation only: {@code null} is allowed (no icon → plugin uses a default); when set it must be
 * non-blank, of the form {@code <type>:<payload>} with a non-empty payload, and {@code <type>} must be
 * a {@linkplain #KNOWN_PREFIXES known prefix}. The payload is deliberately NOT validated (the backend
 * knows no materials/textures). New icon types are added by extending {@link #KNOWN_PREFIXES} — no
 * schema change, the column already holds any string.
 */
public final class RoleDisplayIcon {

    /** Icon type prefixes the backend recognizes. Extend here to allow a new type (no migration needed). */
    public static final Set<String> KNOWN_PREFIXES = Set.of("material", "head-texture", "head-player");

    private RoleDisplayIcon() {}

    /** @throws RoleValidationException if {@code icon} is set but malformed or of an unknown type. */
    public static void validate(String icon) {
        if (icon == null) {
            return; // no icon — plugin falls back to a default
        }
        if (icon.isBlank()) {
            throw new RoleValidationException("display icon must not be blank when set");
        }
        int colon = icon.indexOf(':');
        if (colon <= 0 || colon == icon.length() - 1) {
            throw new RoleValidationException("display icon must be '<type>:<payload>': " + icon);
        }
        String type = icon.substring(0, colon);
        if (!KNOWN_PREFIXES.contains(type)) {
            throw new RoleValidationException("unknown display icon type: " + type);
        }
        // payload (after ':') is opaque — intentionally not validated.
    }

    public static boolean isValid(String icon) {
        try {
            validate(icon);
            return true;
        } catch (RoleValidationException e) {
            return false;
        }
    }
}
