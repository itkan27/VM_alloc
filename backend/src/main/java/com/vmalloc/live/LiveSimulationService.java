package com.vmalloc.live;

import com.vmalloc.model.*;
import com.vmalloc.service.VmClassifierClient;
import com.vmalloc.simulation.CloudSimRunner;
import com.vmalloc.simulation.SimulationObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Starts live simulations on the background executor and forwards their
 * progress to the session as SSE events:
 *
 *   init → placement / placement_failed / spike / migration / host_update … → completed | failed
 */
@Service
public class LiveSimulationService {

    private static final Logger log = LoggerFactory.getLogger(LiveSimulationService.class);

    private final VmClassifierClient classifierClient;
    private final CloudSimRunner cloudSimRunner;
    private final SimulationSessionRegistry registry;
    private final TaskExecutor simExecutor;

    public LiveSimulationService(VmClassifierClient classifierClient,
                                 CloudSimRunner cloudSimRunner,
                                 SimulationSessionRegistry registry,
                                 @Qualifier("simExecutor") TaskExecutor simExecutor) {
        this.classifierClient = classifierClient;
        this.cloudSimRunner = cloudSimRunner;
        this.registry = registry;
        this.simExecutor = simExecutor;
    }

    public SimulationSession start(LiveSimulationRequest request) {
        List<VmRequest> vms = request.vms();
        Map<String, ClassificationResult> classifications = classifierClient.classifyBatch(vms);
        var options = new CloudSimRunner.LiveOptions(
                request.timeScaleOrDefault(), request.arrivalGapOrDefault());

        SimulationSession session = registry.create();
        try {
            simExecutor.execute(() -> run(session, vms, classifications, options));
        } catch (TaskRejectedException e) {
            registry.remove(session.getId());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many live simulations running; try again shortly.");
        }
        log.info("Live session {} started: {} VMs, timeScale={}, arrivalGap={} s.",
                session.getId(), vms.size(), options.timeScale(), options.arrivalGapSeconds());
        return session;
    }

    private void run(SimulationSession session, List<VmRequest> vms,
                     Map<String, ClassificationResult> classifications,
                     CloudSimRunner.LiveOptions options) {
        try {
            SimulationResult result = cloudSimRunner.runLive(
                    vms, classifications, options, publisherFor(session), session::isStopRequested);
            SessionStatus status = session.isStopRequested() ? SessionStatus.STOPPED : SessionStatus.COMPLETED;
            session.finish(status, result, null, "completed",
                    new SessionInfo(session.getId(), status, result, null));
            log.info("Live session {} {}.", session.getId(), status);
        } catch (RuntimeException e) {
            log.error("Live session {} failed", session.getId(), e);
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            session.finish(SessionStatus.FAILED, null, message, "failed",
                    new SessionInfo(session.getId(), SessionStatus.FAILED, null, message));
        }
    }

    private static SimulationObserver publisherFor(SimulationSession session) {
        return new SimulationObserver() {
            @Override
            public void onInit(List<HostSnapshot> hosts) {
                session.publish("init", Map.of("sessionId", session.getId(), "hosts", hosts));
            }

            @Override
            public void onPlacement(PlacementEvent event) {
                session.publish("placement", event);
            }

            @Override
            public void onPlacementFailed(PlacementFailedEvent event) {
                session.publish("placement_failed", event);
            }

            @Override
            public void onHostUpdate(HostUpdateEvent event) {
                session.publish("host_update", event);
            }

            @Override
            public void onSpike(SpikeEvent event) {
                session.publish("spike", event);
            }

            @Override
            public void onMigration(MigrationEvent event) {
                session.publish("migration", event);
            }
        };
    }
}
