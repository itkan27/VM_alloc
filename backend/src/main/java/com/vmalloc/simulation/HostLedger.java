package com.vmalloc.simulation;

import org.cloudsimplus.hosts.Host;

import java.util.*;

/**
 * Per-host resource bookkeeping used by the allocation policies and the migration
 * controller.
 *
 * A VM is booked only after CloudSim has confirmed it on the host and released when
 * CloudSim deallocates it (VM finished, or migrated away), so the ledger always
 * matches the VMs actually running on each host.
 *
 * Two CPU views are kept:
 *   avgMips     — Σ average demand, used for initial placement (static)
 *   currentMips — Σ current demand, updated every tick from the spike simulation
 */
public class HostLedger {

    private static final double EPSILON = 1e-9;

    private static final class Usage {
        final Set<VmProfile> vms = Collections.newSetFromMap(new IdentityHashMap<>());
        double avgMips;
        long ram;
        long bw;
        int spikyVms;
    }

    private final Map<Long, Usage> usage = new HashMap<>();
    private final Map<VmProfile, Double> currentDemand = new IdentityHashMap<>();

    public void book(Host host, VmProfile vm) {
        Usage u = usage.computeIfAbsent(host.getId(), k -> new Usage());
        if (!u.vms.add(vm)) return;
        u.avgMips += vm.avgDemandMips();
        u.ram += vm.ram();
        u.bw += vm.bw();
        if (vm.isSpiky()) u.spikyVms++;
        currentDemand.putIfAbsent(vm, vm.avgDemandMips());
    }

    public void release(Host host, VmProfile vm) {
        Usage u = usage.get(host.getId());
        if (u == null || !u.vms.remove(vm)) return;
        u.avgMips -= vm.avgDemandMips();
        u.ram -= vm.ram();
        u.bw -= vm.bw();
        if (vm.isSpiky()) u.spikyVms--;
        if (u.vms.isEmpty()) usage.remove(host.getId());
    }

    /** Records a VM's demand at the current instant (follows it across migrations) */
    public void setCurrentDemand(VmProfile vm, double mips) {
        currentDemand.put(vm, mips);
    }

    public double currentDemand(VmProfile vm) {
        return currentDemand.getOrDefault(vm, vm.avgDemandMips());
    }

    /** Σ average demand of the VMs on the host (MIPS) */
    public double avgMips(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? 0.0 : u.avgMips;
    }

    /** Σ current demand of the VMs on the host (MIPS) */
    public double currentMips(Host host) {
        Usage u = usage.get(host.getId());
        if (u == null) return 0.0;
        return u.vms.stream().mapToDouble(this::currentDemand).sum();
    }

    /** Current demand ÷ capacity; above 1.0 means the host is overloaded */
    public double currentLoad(Host host) {
        return currentMips(host) / host.getTotalMipsCapacity();
    }

    /** Current CPU utilisation, capped at 1.0 (0.0 – 1.0) */
    public double cpuUtilization(Host host) {
        return Math.min(currentLoad(host), 1.0);
    }

    /** RAM booked on the host (MB) */
    public long ramUsed(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? 0 : u.ram;
    }

    public int vms(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? 0 : u.vms.size();
    }

    public int spikyVms(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? 0 : u.spikyVms;
    }

    /** VMs currently on the host */
    public Collection<VmProfile> vmsOn(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? List.of() : List.copyOf(u.vms);
    }

    /** Would {@code extraMips} more on top of {@code currentMips} still fit the host's CPU? */
    public static boolean fitsCpu(Host host, double currentMips, double extraMips) {
        return currentMips + extraMips <= host.getTotalMipsCapacity() + EPSILON;
    }

    public boolean fitsRamAndBw(Host host, VmProfile vm) {
        Usage u = usage.get(host.getId());
        long ramUsed = u == null ? 0 : u.ram;
        long bwUsed = u == null ? 0 : u.bw;
        return ramUsed + vm.ram() <= host.getRam().getCapacity()
                && bwUsed + vm.bw() <= host.getBw().getCapacity();
    }

    public boolean isEmpty() {
        return usage.isEmpty();
    }
}
