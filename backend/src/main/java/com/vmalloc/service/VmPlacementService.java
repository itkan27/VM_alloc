package com.vmalloc.service;

import com.vmalloc.model.*;
import com.vmalloc.simulation.CloudSimRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * VmPlacementService
 * ──────────────────
 * Orchestrates the full pipeline for a batch placement request:
 *   1. Classify all VMs via the ML service (or fallback heuristic)
 *   2. Run the CloudSim Plus simulation
 *   3. Return the structured result
 */
@Service
public class VmPlacementService {

    private static final Logger log = LoggerFactory.getLogger(VmPlacementService.class);

    private final VmClassifierClient classifierClient;
    private final CloudSimRunner cloudSimRunner;

    public VmPlacementService(VmClassifierClient classifierClient, CloudSimRunner cloudSimRunner) {
        this.classifierClient = classifierClient;
        this.cloudSimRunner = cloudSimRunner;
    }

    /**
     * Execute a one-shot batch simulation.
     *
     * @param requests list of VM placement requests
     * @return simulation results with placements, host metrics, and summary
     */
    public SimulationResult runBatch(List<VmRequest> requests) {
        log.info("Processing batch of {} VMs.", requests.size());

        // Step 1: classify
        Map<String, ClassificationResult> classifications =
                classifierClient.classifyBatch(requests);

        long spikyCount = classifications.values().stream()
                .filter(c -> c.vmType() == VmType.SPIKY).count();
        log.info("Classification complete: {} spiky, {} stable.",
                spikyCount, requests.size() - spikyCount);

        // Step 2: simulate
        SimulationResult result = cloudSimRunner.run(requests, classifications);

        log.info("Simulation done. {} VMs on {} hosts ({} failed).",
                result.placements().size(), result.summary().hostsUsed(),
                result.summary().failedAllocations());

        return result;
    }
}
