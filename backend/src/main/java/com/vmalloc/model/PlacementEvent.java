package com.vmalloc.model;

/**
 * Streamed when a VM is confirmed on a host. Carries the same fields as
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
        boolean allocationSuccess,
        HostSnapshot host,
        double simTime
) {}
