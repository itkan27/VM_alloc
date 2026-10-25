package com.vmalloc.simulation;

import org.cloudsimplus.vms.Vm;

import java.util.Comparator;
import java.util.Optional;

/**
 * Best Fit baseline (as compared in the base paper): the host with the least CPU left
 * (average demand) that can still take the VM. Workload- and zone-unaware; VMs never move.
 */
public class BestFitVmAllocationPolicy extends TrackedAllocationPolicy {

    @Override
    protected Optional<Selection> selectHost(VmProfile vm, Vm cloudSimVm) {
        return candidates(vm, cloudSimVm)
                .filter(h -> HostLedger.fitsCpu(h, ledger.avgMips(h), vm.avgDemandMips()))
                .min(Comparator.comparingDouble(h -> h.getTotalMipsCapacity() - ledger.avgMips(h)))
                .map(h -> new Selection(h, "BEST_FIT"));
    }
}
