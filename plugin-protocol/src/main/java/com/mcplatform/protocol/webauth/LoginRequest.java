package com.mcplatform.protocol.webauth;

/**
 * Login input (webinterface → backend): current Minecraft name + plaintext password. Pure data, JDK-only.
 * The plaintext password rides only in transit (HTTPS body) and is never persisted; this DTO never carries
 * a hash or a secret.
 */
public record LoginRequest(String username, String password) {}
