package com.vmalloc.model;

/**
 * Streamed when a spiky VM's simulated spike starts or ends.
 */
public record SpikeEvent(
        String vmId,
        long cloudSimVmId,
        boolean spiking,
        double cpuPercent,
        double ramPercent,
        double demandMips,

        /** Whether the load crosses a migration threshold */
        boolean thresholdExceeded,

        long hostId,
        Zone zone,
        double simTime
) {}
