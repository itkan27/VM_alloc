package com.vmalloc.controller;

import com.vmalloc.live.LiveSimulationService;
import com.vmalloc.live.SimulationSession;
import com.vmalloc.live.SimulationSessionRegistry;
import com.vmalloc.model.LiveSimulationRequest;
import com.vmalloc.model.SessionInfo;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;

/**
 * LiveSimulationController
 * ────────────────────────
 * Live (streamed) simulations.
 *
 * Endpoints:
 *   POST /api/simulation/sessions              → start a session, returns { sessionId }
 *   GET  /api/simulation/sessions/{id}/stream  → text/event-stream of the session's events
 *   GET  /api/simulation/sessions/{id}         → current status (and result once finished)
 *   POST /api/simulation/sessions/{id}/stop    → stop a running session
 */
@RestController
@RequestMapping("/api/simulation/sessions")
public class LiveSimulationController {

    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final LiveSimulationService liveService;
    private final SimulationSessionRegistry registry;

    public LiveSimulationController(LiveSimulationService liveService, SimulationSessionRegistry registry) {
        this.liveService = liveService;
        this.registry = registry;
    }

    /**
     * Request body:
     * { "vms": [ { "vmId": "vm-001", ... } ], "timeScale": 120, "arrivalGapSeconds": 30 }
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> start(@Valid @RequestBody LiveSimulationRequest request) {
        SimulationSession session = liveService.start(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sessionId", session.getId()));
    }

    @GetMapping(path = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,
                             @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        SimulationSession session = find(id);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onCompletion(() -> session.detach(emitter));
        emitter.onTimeout(() -> session.detach(emitter));
        emitter.onError(e -> session.detach(emitter));
        session.attach(emitter, lastEventId != null ? lastEventId : 0L);
        return emitter;
    }

    @GetMapping("/{id}")
    public SessionInfo status(@PathVariable String id) {
        return find(id).info();
    }

    @PostMapping("/{id}/stop")
    public SessionInfo stop(@PathVariable String id) {
        SimulationSession session = find(id);
        session.requestStop();
        return session.info();
    }

    private SimulationSession find(String id) {
        return registry.get(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown session " + id));
    }
}
