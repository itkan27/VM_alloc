package com.vmalloc.model;

/**
 * CPU, VM-count and energy metrics for a single host after simulation.
 */
public record HostMetric(
        long hostId,
        String hostName,
        Zone zone,

        /** Whether the host ran at least one VM */
        boolean active,

        /** Total host capacity in MIPS (pes × mips_per_pe) */
        double capacityMips,

        /** Fraction of total MIPS consumed by average VM demand (0.0 – 1.0) */
        double cpuUtilization,

        /** Σ peak of spiky VMs + Σ avg of stable VMs: demand if every spiky VM spikes at once */
        double peakDemandMips,

        int totalVms,
        int spikyVms,
        int stableVms,

        /** Measured time from the first VM starting to the last VM finishing (seconds) */
        double activeSeconds,

        double energyKWh
) {}
