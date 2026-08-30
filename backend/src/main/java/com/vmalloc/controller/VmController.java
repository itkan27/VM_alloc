package com.vmalloc.controller;

import com.vmalloc.model.SimulationResult;
import com.vmalloc.model.VmRequest;
import com.vmalloc.service.VmPlacementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * VmController
 * ────────────
 * REST API for the VM placement system.
 *
 * Endpoints:
 *   POST /api/simulation/run   → run a one-shot batch simulation
 *   GET  /api/simulation/ping  → health check
 */
@RestController
@RequestMapping("/api/simulation")
public class VmController {

    private final VmPlacementService placementService;

    public VmController(VmPlacementService placementService) {
        this.placementService = placementService;
    }

    /**
     * Run a one-shot batch VM placement simulation.
     *
     * Request body:
     * [
     *   { "vmId": "vm-001", "minCpu": 5, "maxCpu": 90, "avgCpu": 30,
     *     "mips": 1000, "numCores": 2, "ram": 2048, "bandwidth": 1000, "duration": 3600 },
     *   ...
     * ]
     */
    @PostMapping("/run")
    public ResponseEntity<SimulationResult> runSimulation(
            @Valid @RequestBody List<@Valid VmRequest> requests) {

        if (requests == null || requests.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        SimulationResult result = placementService.runBatch(requests);
        return ResponseEntity.ok(result);
    }

    /**
     * Simple health / ping endpoint.
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "vm-placement-backend"));
    }
}
