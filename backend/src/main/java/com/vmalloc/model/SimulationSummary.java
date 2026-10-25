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

        /** % of placed VMs that ran on an overloaded host at some point (SLA violation rate) */
        double slaViolationRate,

        /** Placed VMs that ran on an overloaded host at some point */
        int slaViolatedVms,

        /** Σ over VMs of seconds spent on an overloaded host */
        double slaViolationVmSeconds,

        /** Mean of the active hosts' time-averaged CPU utilisation (0.0 – 1.0) */
        double avgCpuUtilization,

        /** Mean active time across active hosts (seconds) */
        double avgHostActiveSeconds,

        double totalEnergyKWh,

        /** Mean wall-clock time per host-selection decision (ms) */
        double avgPlacementTimeMs,

        /** Total wall-clock time spent choosing hosts (ms) */
        double totalPlacementTimeMs
) {}
