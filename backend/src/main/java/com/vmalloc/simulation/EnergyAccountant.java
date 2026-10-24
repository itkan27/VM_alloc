package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties.IdlePolicy;
import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.hosts.Host;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integrates host power over simulated time: E = Σ P(u) × Δt.
 *
 * On every clock tick, each host's power is taken from its CloudSim power model
 * at the utilisation recorded in the {@link HostLedger}. Hosts without VMs draw
 * nothing under POWER_OFF and idle power under KEEP_IDLE.
 */
public class EnergyAccountant {

    private static final double JOULES_PER_KWH = 3.6e6;

    private final List<Host> hosts;
    private final HostLedger ledger;
    private final IdlePolicy idlePolicy;

    private final Map<Host, Double> joules = new IdentityHashMap<>();
    private final Map<Host, Double> currentWatts = new IdentityHashMap<>();
    private double lastTime = 0.0;

    public EnergyAccountant(List<Host> hosts, HostLedger ledger, IdlePolicy idlePolicy) {
        this.hosts = hosts;
        this.ledger = ledger;
        this.idlePolicy = idlePolicy;
    }

    public void attach(Simulation simulation) {
        simulation.addOnClockTickListener(info -> onTick(info.getTime()));
    }

    void onTick(double now) {
        double dt = now - lastTime;
        if (dt > 0) {
            for (Host h : hosts) {
                joules.merge(h, currentWatts.getOrDefault(h, 0.0) * dt, Double::sum);
            }
            lastTime = now;
        }
        for (Host h : hosts) {
            currentWatts.put(h, watts(h));
        }
    }

    private double watts(Host h) {
        boolean poweredOn = ledger.vms(h) > 0 || idlePolicy == IdlePolicy.KEEP_IDLE;
        return poweredOn ? h.getPowerModel().getPower(ledger.cpuUtilization(h)) : 0.0;
    }

    public double energyKWh(Host host) {
        return joules.getOrDefault(host, 0.0) / JOULES_PER_KWH;
    }

    public double totalEnergyKWh() {
        return hosts.stream().mapToDouble(this::energyKWh).sum();
    }
}
