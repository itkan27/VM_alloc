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

        /** Whether CloudSim Plus successfully allocated the VM */
        boolean allocationSuccess
) {}
