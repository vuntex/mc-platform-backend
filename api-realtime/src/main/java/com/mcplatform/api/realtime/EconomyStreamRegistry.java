package com.mcplatform.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcplatform.application.economy.BalanceStreamBroadcaster;
import com.mcplatform.application.economy.BalanceStreamView;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory registry of connected economy SSE clients and the fan-out point for live balance changes
 * (spec 007, US4). Implements the {@link BalanceStreamBroadcaster} inbound port so the composition
 * root's Redis bridge can push without this module knowing Redis/wire. One subscription upstream
 * (the bridge), N web clients here — each optionally filtered to a single player. A failed write
 * drops that emitter; the listener thread is never broken.
 */
@Component
public class EconomyStreamRegistry implements BalanceStreamBroadcaster {

    private static final Logger LOG = Logger.getLogger(EconomyStreamRegistry.class.getName());

    private final ObjectMapper json;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public EconomyStreamRegistry(ObjectMapper json) {
        this.json = json;
    }

    /** Register a client. {@code playerFilter} null = all players; otherwise only that player's events. */
    public void register(SseEmitter emitter, UUID playerFilter) {
        Subscriber subscriber = new Subscriber(emitter, playerFilter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(e -> subscribers.remove(subscriber));
    }

    @Override
    public void broadcast(BalanceStreamView view) {
        String payload;
        try {
            payload = json.writeValueAsString(view);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "failed to serialise balance stream view", e);
            return;
        }
        for (Subscriber subscriber : subscribers) {
            if (subscriber.playerFilter != null && !subscriber.playerFilter.equals(view.playerUuid())) {
                continue; // server-side ?player= filter (FR-016)
            }
            try {
                subscriber.emitter.send(SseEmitter.event().data(payload));
            } catch (Exception ex) {
                subscribers.remove(subscriber);
                subscriber.emitter.completeWithError(ex);
            }
        }
    }

    private record Subscriber(SseEmitter emitter, UUID playerFilter) {
    }
}
