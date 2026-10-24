package com.vmalloc.simulation;

import org.cloudsimplus.hosts.Host;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-host resource bookkeeping used by the allocation policies.
 *
 * A VM is booked only after CloudSim has confirmed its creation on the host and
 * released when CloudSim deallocates it, so the ledger always matches the VMs
 * actually running on each host.
 */
public class HostLedger {

    private static final double EPSILON = 1e-9;

    private static final class Usage {
        double avgMips;
        double guaranteedMips;
        long ram;
        long bw;
        int vms;
        int spikyVms;
    }

    private final Map<Long, Usage> usage = new HashMap<>();

    public void book(Host host, VmProfile vm) {
        Usage u = usage.computeIfAbsent(host.getId(), k -> new Usage());
        u.avgMips += vm.avgDemandMips();
        u.guaranteedMips += vm.guaranteedMips();
        u.ram += vm.ram();
        u.bw += vm.bw();
        u.vms++;
        if (vm.isSpiky()) u.spikyVms++;
    }

    public void release(Host host, VmProfile vm) {
        Usage u = usage.get(host.getId());
        if (u == null) return;
        u.avgMips -= vm.avgDemandMips();
        u.guaranteedMips -= vm.guaranteedMips();
        u.ram -= vm.ram();
        u.bw -= vm.bw();
        u.vms--;
        if (vm.isSpiky()) u.spikyVms--;
        if (u.vms == 0) usage.remove(host.getId());
    }

    /** Σ average demand of the VMs on the host (MIPS) */
    public double avgMips(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? 0.0 : u.avgMips;
    }

    /** Σ (peak of spiky VMs + average of stable VMs): host demand if every spiky VM spikes at once */
    public double guaranteedMips(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? 0.0 : u.guaranteedMips;
    }

    public int vms(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? 0 : u.vms;
    }

    public int spikyVms(Host host) {
        Usage u = usage.get(host.getId());
        return u == null ? 0 : u.spikyVms;
    }

    /** Average-demand CPU utilisation of the host (0.0 – 1.0) */
    public double cpuUtilization(Host host) {
        return Math.min(avgMips(host) / host.getTotalMipsCapacity(), 1.0);
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
