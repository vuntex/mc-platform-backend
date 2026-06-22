package com.mcplatform.protocol.report;

import com.mcplatform.protocol.core.Channels;

/** Redis Pub/Sub channel names for the report feature, built on the shared {@link Channels} convention. */
public final class ReportChannels {

    /**
     * Report-change events (created / status changed) for live team notification. Payload is a
     * {@code ReportChangedEvent} carried in a {@link com.mcplatform.protocol.core.MessageEnvelope}.
     * Resolves to {@code mc:report:changed}.
     */
    public static final String CHANGED = Channels.of("report", "changed");

    private ReportChannels() {}
}
