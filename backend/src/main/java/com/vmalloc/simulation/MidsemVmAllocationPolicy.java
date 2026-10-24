package com.vmalloc.simulation;

import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.util.Comparator;
import java.util.Optional;

/**
 * Midsem CPU-Aware Bin-Packing Policy (zone-unaware, no migration), kept as an
 * ablation baseline.
 *
 * Rules:
 *   1. Σ avg demand on a host must NOT exceed the host's total MIPS.
 *   2. Each host gets AT MOST one spiky VM.
 *   3. STABLE VMs use Best-Fit: pack into the host with the least remaining headroom.
 *   4. SPIKY  VMs use First-Fit with isolation: first host that has capacity and no spiky.
 *
 * Spiky VMs never move, so a spike can overload a host shared with stable VMs.
 */
public class MidsemVmAllocationPolicy extends TrackedAllocationPolicy {

    @Override
    protected Optional<Selection> selectHost(VmProfile vm, Vm cloudSimVm) {
        if (vm.isSpiky()) {
            return candidates(vm, cloudSimVm)
                    .filter(h -> HostLedger.fitsCpu(h, ledger.avgMips(h), vm.avgDemandMips()))
                    .filter(h -> ledger.spikyVms(h) == 0)
                    .findFirst()
                    .map(h -> new Selection(h, "SPIKY_FIRST_FIT"));
        }
        return candidates(vm, cloudSimVm)
                .filter(h -> HostLedger.fitsCpu(h, ledger.avgMips(h), vm.avgDemandMips()))
                .min(Comparator.comparingDouble(this::remainingCpu))
                .map(h -> new Selection(h, "STABLE_BEST_FIT"));
    }

    private double remainingCpu(Host h) {
        return h.getTotalMipsCapacity() - ledger.avgMips(h);
    }
}
