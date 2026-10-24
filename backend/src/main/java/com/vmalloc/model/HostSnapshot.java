package com.vmalloc.model;

import java.util.List;

/**
 * Live state of one host during a streamed simulation (VMs currently running on it).
 */
public record HostSnapshot(
        long hostId,
        String hostName,
        Zone zone,
        boolean active,
        double capacityMips,

        /** Current demand ÷ capacity; above 1.0 means the host is overloaded */
        double cpuUtilization,

        int totalVms,
        int spikyVms,
        int stableVms,

        /** IDs of the VMs currently on the host */
        List<String> vmIds
) {}
