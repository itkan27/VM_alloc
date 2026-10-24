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

        /** Spiky VMs placed in the Eco Zone because the Performance Zone was full */
        int spikyOverflowCount,

        /** Hosts that would be overloaded if all their spiky VMs spiked at once */
        int peakOverloadHosts,

        /** Mean CPU utilisation across active hosts (0.0 – 1.0) */
        double avgCpuUtilization,

        /** Mean measured active time across active hosts (seconds) */
        double avgHostActiveSeconds,

        double totalEnergyKWh
) {}
