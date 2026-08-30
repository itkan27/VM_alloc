package com.vmalloc.model;

/**
 * CPU and VM-count metrics for a single host after simulation.
 */
public record HostMetric(
        long hostId,
        String hostName,

        /** Fraction of total MIPS consumed (0.0 – 1.0) */
        double cpuUtilization,

        int totalVms,
        int spikyVms,
        int stableVms,
        double hostDurationSeconds
) {}
