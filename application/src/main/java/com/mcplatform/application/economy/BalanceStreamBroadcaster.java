package com.mcplatform.application.economy;

/**
 * Inbound port (spec 007, US4): pushes a live balance change to all connected web stream subscribers.
 * The realtime layer implements it (fan-out to SSE clients with the per-client player filter); the
 * composition root's Redis bridge calls it. Keeps the realtime module free of Redis/wire knowledge.
 */
public interface BalanceStreamBroadcaster {

    void broadcast(BalanceStreamView view);
}
