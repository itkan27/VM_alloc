package com.vmalloc.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds live simulation sessions in memory. Finished sessions are kept for
 * {@link #RETENTION} so clients can still fetch the result, then dropped.
 */
@Service
public class SimulationSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SimulationSessionRegistry.class);
    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final Map<String, SimulationSession> sessions = new ConcurrentHashMap<>();

    public SimulationSession create() {
        SimulationSession session = new SimulationSession(UUID.randomUUID().toString());
        sessions.put(session.getId(), session);
        return session;
    }

    public Optional<SimulationSession> get(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public void remove(String id) {
        sessions.remove(id);
    }

    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        sessions.values().stream()
                .filter(s -> !s.isFinished())
                .forEach(SimulationSession::heartbeat);
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanup() {
        Instant cutoff = Instant.now().minus(RETENTION);
        sessions.values().removeIf(s -> {
            boolean expired = s.isFinished() && s.getFinishedAt().isBefore(cutoff);
            if (expired) log.debug("Dropping finished session {}", s.getId());
            return expired;
        });
    }
}
