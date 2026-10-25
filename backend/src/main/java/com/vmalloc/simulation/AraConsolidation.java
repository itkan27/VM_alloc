package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import com.vmalloc.simulation.SpikeMigrationController.RunningVm;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.util.*;

/**
 * Base paper ARA — energy optimisation and prediction (Algorithm 1, steps 2b-i and 2c).
 *
 * Every tick each running VM's demand prediction is updated with an exponentially
 * weighted moving average of its observed load (the paper's "Predictive_Model").
 *
 * Every {@code consolidationIntervalSeconds}:
 *   i.   identify underutilised hosts (CPU below {@code underutilizedThresholdPercent}),
 *        least loaded first
 *   ii.  migrate ALL of a host's VMs onto busier active hosts, each target chosen by the
 *        ARA score with migration cost, if every VM fits (predicted demand)
 *   iii. the emptied host has no VMs left and powers down (POWER_OFF idle policy)
 */
public class AraConsolidation implements MigrationStrategy {

    private final AraVmAllocationPolicy policy;
    private final SimProperties.Ara cfg;
    private final Map<Vm, Double> predictedMips = new IdentityHashMap<>();
    private double nextRun;

    public AraConsolidation(AraVmAllocationPolicy policy, SimProperties.Ara cfg) {
        this.policy = policy;
        this.cfg = cfg;
        this.nextRun = cfg.getConsolidationIntervalSeconds();
    }

    @Override
    public void onTick(double now, SpikeMigrationController controller) {
        double s = cfg.getPredictionSmoothing();
        for (RunningVm vm : controller.runningVms()) {
            predictedMips.merge(vm.vm(), vm.load().demandMips(), (old, latest) -> s * latest + (1 - s) * old);
        }

        if (now < nextRun || cfg.getConsolidationIntervalSeconds() <= 0) return;
        nextRun = now + cfg.getConsolidationIntervalSeconds();
        consolidate(controller);
    }

    private void consolidate(SpikeMigrationController controller) {
        HostLedger ledger = policy.getLedger();
        double threshold = cfg.getUnderutilizedThresholdPercent() / 100.0;

        Map<Host, List<RunningVm>> vmsByHost = new IdentityHashMap<>();
        for (RunningVm vm : controller.runningVms()) {
            vmsByHost.computeIfAbsent(vm.vm().getHost(), h -> new ArrayList<>()).add(vm);
        }

        // Hosts busy with migrations are left alone this round
        Set<Host> involved = Collections.newSetFromMap(new IdentityHashMap<>());
        involved.addAll(controller.migrationTargets());
        controller.runningVms().stream()
                .filter(vm -> controller.isInFlight(vm.vm()))
                .forEach(vm -> involved.add(vm.vm().getHost()));

        List<Host> underutilised = vmsByHost.keySet().stream()
                .filter(h -> !involved.contains(h))
                .filter(h -> ledger.currentLoad(h) < threshold)
                .sorted(Comparator.comparingDouble(ledger::currentLoad))
                .toList();

        Map<Host, Double> reservedMips = new IdentityHashMap<>(controller.reservedMips());
        Map<Host, Long> reservedRam = new IdentityHashMap<>();
        Set<Host> evacuated = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Host> receiving = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Host source : underutilised) {
            if (receiving.contains(source)) continue;

            Set<Host> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
            excluded.addAll(evacuated);
            excluded.add(source);
            double sourceLoad = ledger.currentLoad(source);

            Map<Host, Double> trialMips = new IdentityHashMap<>(reservedMips);
            Map<Host, Long> trialRam = new IdentityHashMap<>(reservedRam);
            List<Map.Entry<RunningVm, Host>> plan = new ArrayList<>();

            for (RunningVm vm : vmsByHost.get(source)) {
                double predicted = predictedMips.getOrDefault(vm.vm(), vm.load().demandMips());
                Optional<Host> target = policy.findConsolidationTarget(vm.vm(), vm.profile(), predicted,
                        excluded, sourceLoad, trialMips, trialRam);
                if (target.isEmpty()) {
                    plan.clear();
                    break;
                }
                trialMips.merge(target.get(), predicted, Double::sum);
                trialRam.merge(target.get(), vm.profile().ram(), Long::sum);
                plan.add(Map.entry(vm, target.get()));
            }
            if (plan.isEmpty()) continue;

            String detail = "Host-%d at %.0f%% < %.0f%% CPU".formatted(
                    source.getId(), sourceLoad * 100, cfg.getUnderutilizedThresholdPercent());
            for (Map.Entry<RunningVm, Host> step : plan) {
                double predicted = predictedMips.getOrDefault(step.getKey().vm(), step.getKey().load().demandMips());
                controller.migrate(step.getKey(), step.getValue(),
                        SpikeMigrationController.TRIGGER_CONSOLIDATION, detail, predicted);
                receiving.add(step.getValue());
            }
            reservedMips = trialMips;
            reservedRam = trialRam;
            evacuated.add(source);
        }
    }
}
