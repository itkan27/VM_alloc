package com.vmalloc.model;

/**
 * Result for a single VM after placement simulation. Host and zone describe the
 * VM's initial placement; migrations moved it in and out of the Performance Zone.
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

        /** Host ID the VM was initially placed on (-1 if allocation failed) */
        long hostId,

        /** Human-readable host label */
        String hostName,

        /** Zone of the initial host (null if allocation failed) */
        Zone zone,

        /** Allocation rule that chose the initial host (null if allocation failed) */
        String placementReason,

        /** avgCpu% × mips × cores */
        double cpuDemandMips,

        /** maxCpu% × mips × cores */
        double peakDemandMips,

        /** Completed live migrations of this VM */
        int migrationCount,

        /** Whether CloudSim Plus successfully allocated the VM */
        boolean allocationSuccess
) {}
