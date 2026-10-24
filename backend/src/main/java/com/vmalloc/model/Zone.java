package com.vmalloc.model;

/** Datacenter zone a host belongs to. */
public enum Zone {
    /** High-spec hosts reserved for SPIKY VMs, admitted with full peak headroom */
    PERFORMANCE,
    /** Standard hosts densely packed with STABLE VMs; powered off when idle */
    ECO
}
