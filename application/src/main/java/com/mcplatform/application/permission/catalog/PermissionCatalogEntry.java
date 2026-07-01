package com.mcplatform.application.permission.catalog;

/**
 * One web-interface permission in the discoverable catalog: its exact permission key (the same string
 * the backend gates on) and a human description (German). Pure data, no framework.
 */
public record PermissionCatalogEntry(String key, String description) {
}
