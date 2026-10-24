package com.vmalloc.model;

/**
 * Streamed when a VM is first placed on a host. Carries the same fields as
 * {@link VmPlacementResult} plus the host's state right after the placement.
 */
public record PlacementEvent(
        String vmId,
        long cloudSimVmId,
        VmType vmType,
        double confidence,
        long hostId,
        String hostName,
        Zone zone,
        String placementReason,
        double cpuDemandMips,
        double peakDemandMips,
        int migrationCount,
        boolean allocationSuccess,
        HostSnapshot host,
        double simTime
) {}
