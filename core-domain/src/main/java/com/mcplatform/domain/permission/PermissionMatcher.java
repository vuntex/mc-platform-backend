package com.mcplatform.domain.permission;

import java.util.Collection;
import java.util.Objects;

/**
 * The testable truth of the permission rule: does a set of granted permission strings allow a concrete
 * query permission? Purely additive with wildcards, no negations (FR-002/FR-003/FR-004):
 * <ul>
 *   <li>{@code *} grants everything;</li>
 *   <li>a prefix wildcard {@code feature.*} grants any query starting with {@code feature.} (e.g.
 *       {@code feature.x}, {@code feature.x.y}) — but NOT the bare {@code feature};</li>
 *   <li>otherwise an exact match is required.</li>
 * </ul>
 * Queries are always concrete (e.g. {@code report.view}); wildcards only ever live in stored grants.
 * The jOOQ resolver mirrors this same rule in SQL for the hot path.
 */
public final class PermissionMatcher {

    private static final String GLOBAL = "*";
    private static final String WILDCARD_SUFFIX = ".*";

    private PermissionMatcher() {}

    public static boolean matches(Collection<String> granted, String query) {
        Objects.requireNonNull(query, "query");
        for (String g : granted) {
            if (g == null) {
                continue;
            }
            if (g.equals(GLOBAL) || g.equals(query)) {
                return true;
            }
            if (g.endsWith(WILDCARD_SUFFIX)) {
                // "report.*" -> prefix "report." (keep the dot); matches "report.view", not "reporting".
                String prefix = g.substring(0, g.length() - 1);
                if (query.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
