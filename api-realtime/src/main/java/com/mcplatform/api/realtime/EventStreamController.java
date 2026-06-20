package com.mcplatform.api.realtime;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE endpoint skeleton for live updates. Emits nothing yet — the Redis Pub/Sub
 * fan-out to subscribers is wired later (PROGRESS.md sections 2 &amp; 8).
 */
@RestController
public class EventStreamController {

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // No timeout; placeholder stream that holds the connection open.
        return new SseEmitter(0L);
    }
}
