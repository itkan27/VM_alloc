package com.vmalloc.model;

import java.util.List;

/** Periodic snapshot of every host plus the current simulation clock. */
public record HostUpdateEvent(
        double simTime,
        List<HostSnapshot> hosts
) {}
