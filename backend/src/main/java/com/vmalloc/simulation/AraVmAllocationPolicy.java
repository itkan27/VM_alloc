package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Base Paper: Adaptive Resource-Aware VM Placement (ARA)
 * ══════════════════════════════════════════════════════
 * Kumar, Jain & Pareek, "An Adaptive Resource-Aware Virtual Machine Placement for
 * Energy-Efficient Cloud Computing Environments", IMED 2026 — Algorithm 1.
 *
 * Placement (step 2b):
 *   ii.  Filter candidate hosts on multi-resource thresholds, using the hosts'
 *        CURRENT (monitored) usage: CPU, RAM, storage and bandwidth ≥ VM requirement
 *   iii. Score = α·Available_CPU + β·Available_RAM + γ·Energy_Efficiency − δ·Migration_Cost
 *   iv.  Place the VM on the highest-scoring host
 *
 * Score terms (each 0 – 1):
 *   Available_CPU     = CPU left after placing the VM ÷ host capacity
 *   Available_RAM     = RAM left after placing the VM ÷ host RAM
 *   Energy_Efficiency = MIPS served per watt after placement ÷ best MIPS/W of any host
 *                       (rewards efficient, already-loaded hosts over powering up idle ones)
 *   Migration_Cost    = time to copy the VM's memory to the host ÷ migrationCostNormSeconds
 *                       (0 for a new VM)
 *
 * Energy optimisation (step 2c) runs in {@link AraConsolidation}.
 */
public class AraVmAllocationPolicy extends TrackedAllocationPolicy {

    private final SimProperties.Ara cfg;
    private double bestMipsPerWatt = -1;

    public AraVmAllocationPolicy(SimProperties.Ara cfg) {
        this.cfg = cfg;
    }

    @Override
    protected Optional<Selection> selectHost(VmProfile vm, Vm cloudSimVm) {
        // A new VM has no history, so its predicted demand is its average demand
        double demand = vm.avgDemandMips();
        return candidates(vm, cloudSimVm)
                .filter(h -> HostLedger.fitsCpu(h, ledger.currentMips(h), demand))
                .max(Comparator.comparingDouble(h -> score(h, vm, demand, 0.0, 0L, 0.0)))
                .map(h -> new Selection(h, "ARA_SCORE"));
    }

    /**
     * Consolidation target for a VM leaving an underutilised host: an active host other
     * than its own, not being evacuated, busier than {@code minLoad}, with room for the
     * VM's predicted demand; highest score (including migration cost) wins.
     */
    public Optional<Host> findConsolidationTarget(Vm vm, VmProfile profile, double predictedMips,
                                                  Set<Host> excluded, double minLoad,
                                                  Map<Host, Double> reservedMips, Map<Host, Long> reservedRam) {
        Host source = vm.getHost();
        return candidates(profile, vm)
                .filter(h -> h != source && !excluded.contains(h))
                .filter(h -> ledger.vms(h) > 0 && ledger.currentLoad(h) > minLoad)
                .filter(h -> ledger.ramUsed(h) + reservedRam.getOrDefault(h, 0L) + profile.ram()
                        <= h.getRam().getCapacity())
                .filter(h -> HostLedger.fitsCpu(h, ledger.currentMips(h) + reservedMips.getOrDefault(h, 0.0), predictedMips))
                .max(Comparator.comparingDouble(h -> score(h, profile, predictedMips,
                        reservedMips.getOrDefault(h, 0.0), reservedRam.getOrDefault(h, 0L),
                        migrationSeconds(profile, h))));
    }

    /** ARA heuristic scoring function (Algorithm 1, step 2b-iii) */
    public double score(Host h, VmProfile vm, double demandMips,
                        double reservedMips, long reservedRam, double migrationSeconds) {
        double cap = h.getTotalMipsCapacity();
        double usedAfter = ledger.currentMips(h) + reservedMips + demandMips;
        double availableCpu = Math.max(0, cap - usedAfter) / cap;

        double ramCap = h.getRam().getCapacity();
        double availableRam = Math.max(0, ramCap - ledger.ramUsed(h) - reservedRam - vm.ram()) / ramCap;

        double watts = h.getPowerModel().getPower(Math.min(usedAfter / cap, 1.0));
        double energyEfficiency = watts > 0 ? (Math.min(usedAfter, cap) / watts) / bestMipsPerWatt() : 0.0;

        double migrationCost = cfg.getMigrationCostNormSeconds() > 0
                ? Math.min(1.0, migrationSeconds / cfg.getMigrationCostNormSeconds()) : 0.0;

        return cfg.getAlpha() * availableCpu
                + cfg.getBeta() * availableRam
                + cfg.getGamma() * energyEfficiency
                - cfg.getDelta() * migrationCost;
    }

    /** Time to copy the VM's memory over the target host's network link (s) */
    private static double migrationSeconds(VmProfile vm, Host target) {
        double mbPerSecond = target.getBw().getCapacity() / 8.0;
        return mbPerSecond > 0 ? vm.ram() / mbPerSecond : 0.0;
    }

    private double bestMipsPerWatt() {
        if (bestMipsPerWatt < 0) {
            bestMipsPerWatt = getHostList().stream()
                    .mapToDouble(h -> h.getTotalMipsCapacity() / h.getPowerModel().getPower(1.0))
                    .max().orElse(1.0);
        }
        return bestMipsPerWatt;
    }
}
