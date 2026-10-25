package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import com.vmalloc.model.Zone;
import com.vmalloc.simulation.SpikeMigrationController.RunningVm;
import com.vmalloc.simulation.SpikeSchedule.VmLoad;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Our migration strategy (ZONED policy): for each running SPIKY VM,
 *   ANY threshold crossed while on an Eco host        → migrate to a Performance host
 *   ALL thresholds below while on a Performance host  → migrate back to Eco
 * Thresholds: VM CPU %, VM RAM usage %, VM demand in MIPS (0 disables one).
 */
public class ZoneSpikeMigration implements MigrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ZoneSpikeMigration.class);

    private final ZonedVmAllocationPolicy policy;
    private final SimProperties.Migration thresholds;
    private final Set<Vm> noTargetReported = Collections.newSetFromMap(new IdentityHashMap<>());

    public ZoneSpikeMigration(ZonedVmAllocationPolicy policy, SimProperties.Migration thresholds) {
        this.policy = policy;
        this.thresholds = thresholds;
    }

    @Override
    public void onTick(double now, SpikeMigrationController controller) {
        for (RunningVm vm : controller.runningVms()) {
            if (!vm.profile().isSpiky() || controller.isInFlight(vm.vm())) continue;

            String crossed = crossedThresholds(thresholds, vm.load());
            Host host = vm.vm().getHost();
            Zone zone = policy.zoneOf(host);

            if (crossed != null && zone == Zone.ECO) {
                Optional<Host> target = policy.findPerformanceHost(
                        vm.vm(), vm.profile(), vm.load().demandMips(), controller.reservedMips());
                if (target.isPresent()) {
                    noTargetReported.remove(vm.vm());
                    controller.migrate(vm, target.get(), SpikeMigrationController.TRIGGER_SPIKE,
                            crossed, vm.load().demandMips());
                } else if (noTargetReported.add(vm.vm())) {
                    log.warn("{} crossed a threshold ({}) but no Performance host has room; it stays on Host-{}.",
                            vm.profile().vmId(), crossed, host.getId());
                }
            } else if (crossed == null && zone == Zone.PERFORMANCE) {
                policy.findEcoHost(vm.vm(), vm.profile(), vm.placement().host(), controller.reservedMips())
                        .ifPresent(target -> controller.migrate(vm, target,
                                SpikeMigrationController.TRIGGER_SPIKE_ENDED,
                                "all thresholds below", vm.profile().avgDemandMips()));
            }
        }
    }

    /** Human-readable list of crossed thresholds, or null when none is crossed */
    static String crossedThresholds(SimProperties.Migration t, VmLoad load) {
        List<String> crossed = new ArrayList<>();
        if (t.getCpuThresholdPercent() > 0 && load.cpuPercent() > t.getCpuThresholdPercent())
            crossed.add("CPU %.0f%% > %.0f%%".formatted(load.cpuPercent(), t.getCpuThresholdPercent()));
        if (t.getRamThresholdPercent() > 0 && load.ramPercent() > t.getRamThresholdPercent())
            crossed.add("RAM %.0f%% > %.0f%%".formatted(load.ramPercent(), t.getRamThresholdPercent()));
        if (t.getMipsThreshold() > 0 && load.demandMips() > t.getMipsThreshold())
            crossed.add("MIPS %.0f > %.0f".formatted(load.demandMips(), t.getMipsThreshold()));
        return crossed.isEmpty() ? null : String.join(", ", crossed);
    }
}
