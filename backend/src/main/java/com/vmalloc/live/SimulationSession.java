package com.vmalloc.live;

import com.vmalloc.model.SessionInfo;
import com.vmalloc.model.SessionStatus;
import com.vmalloc.model.SimulationResult;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One live simulation: its status, every event published so far (replayed to
 * late or reconnecting clients) and the SSE emitters currently attached.
 *
 * Events carry increasing ids, so a reconnecting EventSource that sends
 * Last-Event-ID only receives what it missed. All sends happen under the
 * session lock, which keeps event order identical for every client.
 */
public class SimulationSession {

    private record SessionEvent(long id, String type, Object data) {}

    private final String id;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private final List<SessionEvent> events = new ArrayList<>();
    private final List<SseEmitter> emitters = new ArrayList<>();
    private long nextEventId = 1;

    private volatile SessionStatus status = SessionStatus.RUNNING;
    private volatile SimulationResult result;
    private volatile String error;
    private volatile Instant finishedAt;

    public SimulationSession(String id) {
        this.id = id;
    }

    // ─── Publishing ──────────────────────────────────────────────────────────

    public synchronized void publish(String type, Object data) {
        SessionEvent event = new SessionEvent(nextEventId++, type, data);
        events.add(event);
        emitters.removeIf(emitter -> !send(emitter, event));
    }

    /** Publishes the final event and closes every stream */
    public synchronized void finish(SessionStatus finalStatus, SimulationResult finalResult,
                                    String errorMessage, String type, Object data) {
        this.result = finalResult;
        this.error = errorMessage;
        this.finishedAt = Instant.now();
        this.status = finalStatus;
        publish(type, data);
        List.copyOf(emitters).forEach(SseEmitter::complete);
        emitters.clear();
    }

    // ─── Subscribing ─────────────────────────────────────────────────────────

    /** Replays events after {@code lastEventId}, then streams new ones (or closes if finished) */
    public synchronized void attach(SseEmitter emitter, long lastEventId) {
        for (SessionEvent event : events) {
            if (event.id() > lastEventId && !send(emitter, event)) return;
        }
        if (isFinished()) {
            emitter.complete();
        } else {
            emitters.add(emitter);
        }
    }

    public synchronized void detach(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    /** Sends an SSE comment so proxies keep idle connections open */
    public synchronized void heartbeat() {
        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
                return false;
            } catch (IOException | IllegalStateException e) {
                return true;
            }
        });
    }

    private static boolean send(SseEmitter emitter, SessionEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.id()))
                    .name(event.type())
                    .data(event.data(), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    // ─── State ───────────────────────────────────────────────────────────────

    public void requestStop() {
        stopRequested.set(true);
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    public boolean isFinished() {
        return status != SessionStatus.RUNNING;
    }

    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }

    public SessionInfo info() {
        return new SessionInfo(id, status, result, error);
    }
}
