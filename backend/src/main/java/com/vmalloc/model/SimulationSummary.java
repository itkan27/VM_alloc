package com.vmalloc.model;

/** Aggregate simulation statistics. */
public record SimulationSummary(
        int totalVms,
        int spikyVms,
        int stableVms,
        int hostsUsed,
        int failedAllocations,
        double avgCpuUtilization,
        double simulationTimeSeconds
) {}
