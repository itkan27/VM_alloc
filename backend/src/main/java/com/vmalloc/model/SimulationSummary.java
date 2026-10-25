package com.vmalloc.model;

/** Aggregate simulation statistics. */
public record SimulationSummary(
        int totalVms,
        int spikyVms,
        int stableVms,
        int hostsUsed,
        int performanceHostsUsed,
        int ecoHostsUsed,
        int failedAllocations,

        /** Completed live migrations (both directions) */
        int migrations,

        /** Simulated spikes that started */
        int spikes,

        /** Σ over hosts of seconds spent overloaded (demand > capacity) */
        double overloadHostSeconds,

        /** Mean of the active hosts' time-averaged CPU utilisation (0.0 – 1.0) */
        double avgCpuUtilization,

        /** Mean active time across active hosts (seconds) */
        double avgHostActiveSeconds,

        double totalEnergyKWh
) {}
