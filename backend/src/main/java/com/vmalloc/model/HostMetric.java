package com.vmalloc.model;

/**
 * CPU, VM-count, migration and energy metrics for a single host over the whole run.
 */
public record HostMetric(
        long hostId,
        String hostName,
        Zone zone,

        /** Whether the host ran at least one VM */
        boolean active,

        /** Total host capacity in MIPS (pes × mips_per_pe) */
        double capacityMips,

        /** Time-averaged CPU utilisation while the host had VMs (0.0 – 1.0) */
        double cpuUtilization,

        /** Highest demand ÷ capacity seen; above 1.0 means the host was overloaded */
        double maxCpuUtilization,

        /** Distinct VMs that ran on the host */
        int totalVms,
        int spikyVms,
        int stableVms,

        /** Simulated seconds the host had at least one VM */
        double activeSeconds,

        /** Simulated seconds the host's demand exceeded its capacity */
        double overloadSeconds,

        int migrationsIn,
        int migrationsOut,
        double energyKWh
) {}
