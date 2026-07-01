package com.mcplatform.protocol.permission;

/**
 * One web-interface permission in the catalog: its exact key (the string assigned to roles) and a
 * human description. Pure data (JDK only); field names are the wire contract.
 */
public record PermissionInfoResponse(String key, String description) {
}
