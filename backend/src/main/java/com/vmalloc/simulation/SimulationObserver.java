package com.vmalloc.simulation;

import com.vmalloc.model.HostSnapshot;
import com.vmalloc.model.HostUpdateEvent;
import com.vmalloc.model.PlacementEvent;
import com.vmalloc.model.PlacementFailedEvent;

import java.util.List;

/** Receives progress from a live simulation run (called on the simulation thread). */
public interface SimulationObserver {

    /** Datacenter topology before any VM arrives */
    void onInit(List<HostSnapshot> hosts);

    void onPlacement(PlacementEvent event);

    void onPlacementFailed(PlacementFailedEvent event);

    /** Throttled snapshot of every host, sent while the simulation clock advances */
    void onHostUpdate(HostUpdateEvent event);
}
