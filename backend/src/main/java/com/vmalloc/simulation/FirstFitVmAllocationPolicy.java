package com.vmalloc.simulation;

import org.cloudsimplus.vms.Vm;

import java.util.Optional;

/**
 * First Fit baseline (as compared in the base paper): the first host, in host order,
 * whose CPU (average demand), RAM and bandwidth can take the VM. Workload- and
 * zone-unaware; VMs never move.
 */
public class FirstFitVmAllocationPolicy extends TrackedAllocationPolicy {

    @Override
    protected Optional<Selection> selectHost(VmProfile vm, Vm cloudSimVm) {
        return candidates(vm, cloudSimVm)
                .filter(h -> HostLedger.fitsCpu(h, ledger.avgMips(h), vm.avgDemandMips()))
                .findFirst()
                .map(h -> new Selection(h, "FIRST_FIT"));
    }
}
