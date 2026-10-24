package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import com.vmalloc.config.SimProperties.PerformanceStrategy;
import com.vmalloc.model.Zone;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Performance / Eco Zone Allocation Policy
 * ════════════════════════════════════════
 *
 * Every host keeps "peak-safe" headroom:
 *   Σ peak(spiky VMs) + Σ avg(stable VMs)  ≤  host capacity (pes × mips_per_pe)
 * so no host is overloaded even if all its spiky VMs spike at the same time.
 *
 * Rules:
 *   SPIKY  1. Performance host with peak-safe room (FIRST_FIT packs, WORST_FIT spreads),
 *             up to maxSpikyPerHost spiky VMs per host (0 = no limit).
 *          2. Overflow: Eco host carrying no other spiky VM, with peak-safe room.
 *   STABLE 1. Eco host, Best-Fit (least remaining headroom) for maximum consolidation.
 *          2. Overflow: Performance host, Best-Fit, only if peak-safe room remains.
 */
public class ZonedVmAllocationPolicy extends TrackedAllocationPolicy {

    public static final String SPIKY_OVERFLOW_ECO = "SPIKY_OVERFLOW_ECO";

    private final SimProperties.ZoneRules rules;

    public ZonedVmAllocationPolicy(SimProperties.ZoneRules rules) {
        this.rules = rules;
    }

    @Override
    protected Optional<Selection> selectHost(VmProfile vm, Vm cloudSimVm) {
        return vm.isSpiky() ? selectForSpiky(vm, cloudSimVm) : selectForStable(vm, cloudSimVm);
    }

    private Optional<Selection> selectForSpiky(VmProfile vm, Vm cloudSimVm) {
        int maxSpiky = rules.getPerformance().getMaxSpikyPerHost();
        PerformanceStrategy strategy = rules.getPerformance().getStrategy();

        Stream<Host> perfHosts = candidates(vm, cloudSimVm, Zone.PERFORMANCE)
                .filter(h -> peakSafe(h, vm))
                .filter(h -> maxSpiky <= 0 || ledger.spikyVms(h) < maxSpiky);
        Optional<Host> perf = strategy == PerformanceStrategy.WORST_FIT
                ? perfHosts.max(Comparator.comparingDouble(this::headroom))
                : perfHosts.findFirst();
        if (perf.isPresent()) {
            return perf.map(h -> new Selection(h, "PERF_" + strategy));
        }

        if (!rules.isAllowSpikyOverflowToEco()) return Optional.empty();
        return candidates(vm, cloudSimVm, Zone.ECO)
                .filter(h -> ledger.spikyVms(h) == 0)
                .filter(h -> peakSafe(h, vm))
                .findFirst()
                .map(h -> new Selection(h, SPIKY_OVERFLOW_ECO));
    }

    private Optional<Selection> selectForStable(VmProfile vm, Vm cloudSimVm) {
        Optional<Host> eco = bestFit(candidates(vm, cloudSimVm, Zone.ECO), vm);
        if (eco.isPresent()) {
            return eco.map(h -> new Selection(h, "ECO_BEST_FIT"));
        }

        if (!rules.isAllowStableOverflowToPerf()) return Optional.empty();
        return bestFit(candidates(vm, cloudSimVm, Zone.PERFORMANCE), vm)
                .map(h -> new Selection(h, "STABLE_OVERFLOW_PERF"));
    }

    private Optional<Host> bestFit(Stream<Host> hosts, VmProfile vm) {
        return hosts.filter(h -> peakSafe(h, vm))
                .min(Comparator.comparingDouble(this::headroom));
    }

    /** Would the host still survive all its spiky VMs peaking at once after adding this VM? */
    private boolean peakSafe(Host h, VmProfile vm) {
        return HostLedger.fitsCpu(h, ledger.guaranteedMips(h), vm.guaranteedMips());
    }

    /** Peak-safe MIPS still free on the host */
    private double headroom(Host h) {
        return h.getTotalMipsCapacity() - ledger.guaranteedMips(h);
    }
}
