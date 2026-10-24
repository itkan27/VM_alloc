package com.vmalloc.simulation;

import org.cloudsimplus.vms.Vm;

/** Notified each time a policy books a VM on a host CloudSim has confirmed. */
@FunctionalInterface
public interface PlacementListener {

    PlacementListener NOOP = (vm, profile, placement) -> {};

    void onPlaced(Vm vm, VmProfile profile, TrackedAllocationPolicy.Placement placement);
}
