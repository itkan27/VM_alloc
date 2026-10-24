package com.vmalloc.simulation;

import com.vmalloc.model.Zone;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Decoupled Performance / Eco Zone Allocation Policy
 * ══════════════════════════════════════════════════
 *
 * Initial placement — Eco Zone only (the Performance Zone starts empty):
 *   Σ avg demand on a host ≤ host capacity (pes × mips_per_pe)
 *   SPIKY  → First-Fit on an Eco host carrying no other spiky VM (at most one per host)
 *   STABLE → Best-Fit on an Eco host (least remaining headroom) for maximum consolidation
 *
 * Live migration targets (chosen for {@link SpikeMigrationController}):
 *   to Performance → first Performance host whose CURRENT load + the VM's current
 *                    demand fits, so a spiking VM gets its full peak
 *   back to Eco    → the VM's original host if it still qualifies, else the first Eco
 *                    host with no spiky VM and average-demand room
 */
public class ZonedVmAllocationPolicy extends TrackedAllocationPolicy {

    @Override
    protected Optional<Selection> selectHost(VmProfile vm, Vm cloudSimVm) {
        if (vm.isSpiky()) {
            return candidates(vm, cloudSimVm, Zone.ECO)
                    .filter(h -> ledger.spikyVms(h) == 0)
                    .filter(h -> HostLedger.fitsCpu(h, ledger.avgMips(h), vm.avgDemandMips()))
                    .findFirst()
                    .map(h -> new Selection(h, "ECO_SPIKY_FIRST_FIT"));
        }
        return candidates(vm, cloudSimVm, Zone.ECO)
                .filter(h -> HostLedger.fitsCpu(h, ledger.avgMips(h), vm.avgDemandMips()))
                .min(Comparator.comparingDouble(h -> h.getTotalMipsCapacity() - ledger.avgMips(h)))
                .map(h -> new Selection(h, "ECO_BEST_FIT"));
    }

    /**
     * Performance host for a spiking VM.
     *
     * @param demandMips   the VM's current demand
     * @param reservedMips MIPS already promised to migrations still in flight, per host
     */
    public Optional<Host> findPerformanceHost(Vm vm, VmProfile profile, double demandMips,
                                              Map<Host, Double> reservedMips) {
        return candidates(profile, vm, Zone.PERFORMANCE)
                .filter(h -> HostLedger.fitsCpu(h,
                        ledger.currentMips(h) + reservedMips.getOrDefault(h, 0.0), demandMips))
                .findFirst();
    }

    /**
     * Eco host for a VM whose spike is over: its original host when possible.
     *
     * @param reservedMips average MIPS already promised to migrations still in flight, per host
     */
    public Optional<Host> findEcoHost(Vm vm, VmProfile profile, Host home, Map<Host, Double> reservedMips) {
        return candidates(profile, vm, Zone.ECO)
                .filter(h -> ledger.spikyVms(h) == 0)
                .filter(h -> HostLedger.fitsCpu(h,
                        ledger.avgMips(h) + reservedMips.getOrDefault(h, 0.0), profile.avgDemandMips()))
                .min(Comparator.comparingInt(h -> h == home ? 0 : 1));
    }
}
