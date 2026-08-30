package com.vmalloc.simulation;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyAbstract;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * CPU-Aware Bin-Packing VM Allocation Policy
 * ═══════════════════════════════════════════
 *
 * Formula:
 *   vm_demand   = avgCpu% × mips × cores          (per VM)
 *   host_capacity = pes × mips_per_pe              (per host)
 *   Constraint: Σ vm_demand  ≤  host_capacity      (per host)
 *
 * Rules:
 *   1. The sum of all VM demands on a host must NOT exceed the host's total MIPS.
 *   2. Each host gets AT MOST one spiky VM.
 *   3. STABLE VMs use Best-Fit: pack into the host with the least remaining headroom.
 *   4. SPIKY  VMs use First-Fit with isolation: first host that has capacity and no spiky.
 *
 * ALL resource tracking (CPU, RAM, BW) is done by US, not by CloudSim,
 * because CloudSim's internal tracking is unreliable during batch allocation
 * at time 0 (VMs are not committed to hosts until after all are evaluated).
 */
public class CustomVmAllocationPolicy extends VmAllocationPolicyAbstract {

    private static final Logger log = LoggerFactory.getLogger(CustomVmAllocationPolicy.class);

    // ─── Per-VM registration data (set before simulation starts) ─────────────

    /** VMs classified as SPIKY (identity-based to avoid ID mismatch at t=0) */
    private final Set<Vm> spikyVms = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Per-VM CPU demand in MIPS = avgCpu% × mips × cores */
    private final Map<Vm, Double> vmCpuDemandMips = new IdentityHashMap<>();

    // ─── Per-Host tracking (updated immediately on each placement) ───────────

    /** Host ID → accumulated CPU demand (MIPS) */
    private final Map<Long, Double> hostCpuUsed = new HashMap<>();

    /** Host ID → accumulated RAM used (MB) */
    private final Map<Long, Long> hostRamUsed = new HashMap<>();

    /** Host ID → accumulated BW used (Mbps) */
    private final Map<Long, Long> hostBwUsed = new HashMap<>();

    /** Host IDs that already carry a spiky VM */
    private final Set<Long> hostsWithSpikyVm = new HashSet<>();

    // ─── Registration API (called by CloudSimRunner before simulation.start()) ──

    public void addSpikyVm(Vm vm) {
        spikyVms.add(vm);
    }

    public void setVmCpuDemand(Vm vm, double demandMips) {
        vmCpuDemandMips.put(vm, demandMips);
    }

    // ─── Core allocation logic ────────────────────────────────────────────────

    @Override
    public Optional<Host> defaultFindHostForVm(final Vm vm) {
        boolean isSpiky = spikyVms.contains(vm);
        double cpuDemand = vmCpuDemandMips.getOrDefault(vm, vm.getTotalMipsCapacity());
        long   ramNeeded = vm.getRam().getCapacity();
        long   bwNeeded  = vm.getBw().getCapacity();

        log.info("Placing Vm {} ({}) | type={} | cpuDemand={} MIPS | ram={} MB | bw={} Mbps",
                vm.getId(), vm.getDescription(), isSpiky ? "SPIKY" : "STABLE",
                String.format("%.1f", cpuDemand), ramNeeded, bwNeeded);

        Optional<Host> chosen;
        if (isSpiky) {
            // SPIKY: First-Fit — first host with capacity AND no existing spiky VM
            chosen = getHostList().stream()
                    .filter(h -> fits(h, cpuDemand, ramNeeded, bwNeeded))
                    .filter(h -> !hostsWithSpikyVm.contains(h.getId()))
                    .findFirst();
        } else {
            // STABLE: Best-Fit — host with LEAST remaining CPU that still fits
            chosen = getHostList().stream()
                    .filter(h -> fits(h, cpuDemand, ramNeeded, bwNeeded))
                    .min(Comparator.comparingDouble(h -> remainingCpu(h)));
        }

        // Immediately update ALL trackers so the next VM sees correct state
        chosen.ifPresent(host -> {
            long hid = host.getId();

            double prevCpu = hostCpuUsed.getOrDefault(hid, 0.0);
            double nextCpu = prevCpu + cpuDemand;
            hostCpuUsed.put(hid, nextCpu);

            long prevRam = hostRamUsed.getOrDefault(hid, 0L);
            hostRamUsed.put(hid, prevRam + ramNeeded);

            long prevBw = hostBwUsed.getOrDefault(hid, 0L);
            hostBwUsed.put(hid, prevBw + bwNeeded);

            if (isSpiky) hostsWithSpikyVm.add(hid);

            double hostCap = host.getTotalMipsCapacity();
            log.info("  -> Host-{} | CPU {}/{} MIPS ({}%) | RAM {}/{} MB | BW {}/{} Mbps",
                    hid,
                    String.format("%.0f", nextCpu), (long) hostCap,
                    String.format("%.1f", nextCpu / hostCap * 100.0),
                    hostRamUsed.get(hid), host.getRam().getCapacity(),
                    hostBwUsed.get(hid), host.getBw().getCapacity());
        });

        if (chosen.isEmpty()) {
            log.warn("  -> NO HOST found for Vm {} ({})!", vm.getId(), vm.getDescription());
        }

        return chosen;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Can host h accept a VM with the given CPU/RAM/BW demands?
     * All checks use OUR tracked values, not CloudSim's internal state.
     */
    private boolean fits(Host h, double cpuDemand, long ramNeeded, long bwNeeded) {
        long hid = h.getId();

        // CPU: Σ vm_demand + new_demand ≤ host_capacity (pes × mips_per_pe)
        double cpuUsed = hostCpuUsed.getOrDefault(hid, 0.0);
        if (cpuUsed + cpuDemand > h.getTotalMipsCapacity()) return false;

        // RAM: Σ vm_ram + new_ram ≤ host_ram
        long ramUsed = hostRamUsed.getOrDefault(hid, 0L);
        if (ramUsed + ramNeeded > h.getRam().getCapacity()) return false;

        // BW: Σ vm_bw + new_bw ≤ host_bw
        long bwUsed = hostBwUsed.getOrDefault(hid, 0L);
        if (bwUsed + bwNeeded > h.getBw().getCapacity()) return false;

        return true;
    }

    /** Remaining CPU headroom in MIPS (for Best-Fit sorting) */
    private double remainingCpu(Host h) {
        return h.getTotalMipsCapacity() - hostCpuUsed.getOrDefault(h.getId(), 0.0);
    }
}
