package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties.IdlePolicy;
import org.cloudsimplus.hosts.Host;

import java.util.*;

/**
 * Integrates per-host statistics over simulated time from the {@link HostLedger}:
 *   energy        E = Σ P(u) × Δt, using each host's CloudSim power model
 *   active time   seconds the host had at least one VM
 *   utilisation   time-averaged while active, plus the highest load seen
 *   overload      seconds the host's demand exceeded its capacity
 *
 * Call {@link #integrate(double)} at each clock tick before VM loads change, then
 * {@link #sample()} after, so every interval is charged at the state it actually had.
 * Hosts without VMs draw nothing under POWER_OFF and idle power under KEEP_IDLE.
 */
public class HostMonitor {

    private static final double JOULES_PER_KWH = 3.6e6;
    private static final double EPSILON = 1e-9;

    private static final class Stats {
        double joules;
        double activeSeconds;
        double utilizationSeconds;
        double overloadSeconds;
        double maxLoad;
        final Set<VmProfile> seenVms = Collections.newSetFromMap(new IdentityHashMap<>());

        // State since the last sample
        boolean hasVms;
        double load;
        double watts;
    }

    private final List<Host> hosts;
    private final HostLedger ledger;
    private final IdlePolicy idlePolicy;
    private final Map<Host, Stats> stats = new IdentityHashMap<>();
    private double lastTime = 0.0;

    public HostMonitor(List<Host> hosts, HostLedger ledger, IdlePolicy idlePolicy) {
        this.hosts = hosts;
        this.ledger = ledger;
        this.idlePolicy = idlePolicy;
        hosts.forEach(h -> stats.put(h, new Stats()));
    }

    /** Charges the interval since the previous tick at the previously sampled state */
    public void integrate(double now) {
        double dt = now - lastTime;
        if (dt <= 0) return;
        for (Stats s : stats.values()) {
            s.joules += s.watts * dt;
            if (s.hasVms) {
                s.activeSeconds += dt;
                s.utilizationSeconds += Math.min(s.load, 1.0) * dt;
                if (s.load > 1.0 + EPSILON) s.overloadSeconds += dt;
            }
        }
        lastTime = now;
    }

    /** Captures each host's current load and power draw from the ledger */
    public void sample() {
        for (Host h : hosts) {
            Stats s = stats.get(h);
            s.hasVms = ledger.vms(h) > 0;
            s.load = ledger.currentLoad(h);
            boolean poweredOn = s.hasVms || idlePolicy == IdlePolicy.KEEP_IDLE;
            s.watts = poweredOn ? h.getPowerModel().getPower(Math.min(s.load, 1.0)) : 0.0;
            if (s.hasVms) {
                s.maxLoad = Math.max(s.maxLoad, s.load);
                s.seenVms.addAll(ledger.vmsOn(h));
            }
        }
    }

    public double energyKWh(Host h) {
        return stats.get(h).joules / JOULES_PER_KWH;
    }

    public double totalEnergyKWh() {
        return hosts.stream().mapToDouble(this::energyKWh).sum();
    }

    public double activeSeconds(Host h) {
        return stats.get(h).activeSeconds;
    }

    /** Time-averaged CPU utilisation while the host had VMs (0.0 – 1.0) */
    public double avgUtilization(Host h) {
        Stats s = stats.get(h);
        return s.activeSeconds > 0 ? s.utilizationSeconds / s.activeSeconds : 0.0;
    }

    public double maxLoad(Host h) {
        return stats.get(h).maxLoad;
    }

    public double overloadSeconds(Host h) {
        return stats.get(h).overloadSeconds;
    }

    /** Distinct VMs that ran on the host */
    public Collection<VmProfile> seenVms(Host h) {
        return List.copyOf(stats.get(h).seenVms);
    }
}
