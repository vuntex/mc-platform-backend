package com.mcplatform.protocol.core;

/**
 * Convention for Redis Pub/Sub channel names shared by backend nodes and the plugin:
 * {@code mc:{feature}:{topic}}. Feature modules expose their own channel constants built via
 * {@link #of(String, String)} (see {@code EconomyChannels}); this keeps every feature on the same
 * naming scheme instead of hand-spelling channel strings.
 */
public final class Channels {

    public static final String PREFIX = "mc";
    private static final String SEP = ":";

    private Channels() {}

    /** Builds a channel name {@code mc:<feature>:<topic>} following the shared convention. */
    public static String of(String feature, String topic) {
        requirePart(feature, "feature");
        requirePart(topic, "topic");
        return PREFIX + SEP + feature + SEP + topic;
    }

    private static void requirePart(String part, String name) {
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (part.contains(SEP)) {
            throw new IllegalArgumentException(name + " must not contain '" + SEP + "': " + part);
        }
    }
}
