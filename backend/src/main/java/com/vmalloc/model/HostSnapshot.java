package com.vmalloc.model;

/**
 * Live state of one host during a streamed simulation (VMs currently running on it).
 */
public record HostSnapshot(
        long hostId,
        String hostName,
        Zone zone,
        boolean active,
        double capacityMips,

        /** Fraction of total MIPS consumed by average VM demand (0.0 – 1.0) */
        double cpuUtilization,

        /** Σ peak of spiky VMs + Σ avg of stable VMs */
        double peakDemandMips,

        int totalVms,
        int spikyVms,
        int stableVms
) {}
