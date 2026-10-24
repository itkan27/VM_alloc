package com.vmalloc.model;

import java.util.List;

/**
 * Top-level response returned to the React frontend after a simulation run.
 */
public record SimulationResult(
        List<VmPlacementResult> placements,
        List<HostMetric> hostMetrics,
        List<MigrationEvent> migrations,
        SimulationSummary summary
) {}
