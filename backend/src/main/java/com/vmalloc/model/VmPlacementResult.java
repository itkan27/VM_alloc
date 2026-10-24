package com.vmalloc.model;

/**
 * Result for a single VM after placement simulation.
 */
public record VmPlacementResult(
        /** User-supplied unique ID, e.g. "vm-001" */
        String vmId,

        /** Internal CloudSim Plus VM index */
        long cloudSimVmId,

        /** Classification assigned by the MLP */
        VmType vmType,

        /** ML model confidence score */
        double confidence,

        /** Host ID the VM was placed on (-1 if allocation failed) */
        long hostId,

        /** Human-readable host label */
        String hostName,

        /** Zone of the host the VM was placed on (null if allocation failed) */
        Zone zone,

        /** Allocation rule that chose the host, e.g. PERF_FIRST_FIT (null if allocation failed) */
        String placementReason,

        /** avgCpu% × mips × cores */
        double cpuDemandMips,

        /** maxCpu% × mips × cores */
        double peakDemandMips,

        /** Whether CloudSim Plus successfully allocated the VM */
        boolean allocationSuccess
) {}
