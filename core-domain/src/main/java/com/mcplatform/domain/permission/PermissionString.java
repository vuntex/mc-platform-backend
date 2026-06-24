package com.mcplatform.domain.permission;

/**
 * Backend-authoritative validation of a stored permission string (FR-014). Mirrors the syntax the
 * {@link PermissionMatcher} understands: a bare {@code *}, an exact token, or a {@code feature.*} prefix
 * wildcard — dot-separated, no negation. Rejects blank, whitespace, negation ({@code -}) and empty
 * segments. Pure core-domain (framework-free); raises {@link RoleValidationException} (→ HTTP 422).
 */
public final class PermissionString {

    private PermissionString() {}

    public static void validate(String permission) {
        if (permission == null || permission.isBlank()) {
            throw new RoleValidationException("permission must not be blank");
        }
        if (permission.chars().anyMatch(Character::isWhitespace)) {
            throw new RoleValidationException("permission must not contain whitespace: '" + permission + "'");
        }
        if (permission.startsWith("-") || permission.startsWith("!")) {
            throw new RoleValidationException("permission negation is not allowed: '" + permission + "'");
        }
        if (permission.equals("*")) {
            return; // the global wildcard is valid on its own
        }
        if (permission.startsWith(".") || permission.contains("..")) {
            throw new RoleValidationException("permission has an empty segment: '" + permission + "'");
        }
        // a trailing dot is only valid as part of the ".*" prefix wildcard
        if (permission.endsWith(".")) {
            throw new RoleValidationException("permission has an empty segment: '" + permission + "'");
        }
    }
}
