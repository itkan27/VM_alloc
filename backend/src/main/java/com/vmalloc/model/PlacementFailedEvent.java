package com.vmalloc.model;

/**
 * Streamed once when no host can take a VM. The broker may still retry and
 * place it later, in which case a {@link PlacementEvent} follows.
 */
public record PlacementFailedEvent(
        String vmId,
        long cloudSimVmId,
        VmType vmType,
        double confidence,
        double cpuDemandMips,
        double peakDemandMips,
        double simTime
) {}
