package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import com.vmalloc.model.MigrationEvent;
import com.vmalloc.model.SpikeEvent;
import com.vmalloc.simulation.SpikeSchedule.VmLoad;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Drives the spike simulation and executes live migrations.
 *
 * Every clock tick:
 *   1. compute each running VM's load from the {@link SpikeSchedule} and record it in the ledger
 *   2. report spike start / end
 *   3. let the policy's {@link MigrationStrategy} (if any) request migrations:
 *        ZONED → {@link ZoneSpikeMigration}  (spiky VMs Eco ⇄ Performance on thresholds)
 *        ARA   → {@link AraConsolidation}    (empty underutilised hosts, base paper)
 *
 * Migrations use CloudSim's live migration (memory copied over the target host's
 * bandwidth). A VM is not migrated again while a migration of it is in flight, and
 * the target's CPU is reserved until the migration completes.
 */
public class SpikeMigrationController {

    private static final Logger log = LoggerFactory.getLogger(SpikeMigrationController.class);

    public static final String TRIGGER_SPIKE = "SPIKE";
    public static final String TRIGGER_SPIKE_ENDED = "SPIKE_ENDED";
    public static final String TRIGGER_CONSOLIDATION = "CONSOLIDATION";

    /** Receives spike and migration events (called on the simulation thread) */
    public interface Listener {
        Listener NOOP = new Listener() {
            @Override public void onSpike(SpikeEvent event) {}
            @Override public void onMigration(MigrationEvent event) {}
        };

        void onSpike(SpikeEvent event);

        void onMigration(MigrationEvent event);
    }

    /** A VM executing its cloudlet during the current tick, with its load right now */
    public record RunningVm(Vm vm, VmProfile profile, VmLoad load, TrackedAllocationPolicy.Placement placement) {}

    private record InFlight(Host from, Host to, String trigger, String detail,
                            VmLoad load, double requestedAt, double reservedMips) {}

    private final TrackedAllocationPolicy policy;
    private final MigrationStrategy strategy;
    private final Datacenter datacenter;
    private final SpikeSchedule schedule;
    private final SimProperties.Migration thresholds;
    private final List<? extends Vm> vms;
    private Listener listener = Listener.NOOP;

    private final Map<Vm, Boolean> spiking = new IdentityHashMap<>();
    private final Map<Vm, InFlight> inFlight = new IdentityHashMap<>();
    private final Map<Vm, Integer> migrationCounts = new IdentityHashMap<>();
    private final List<MigrationEvent> migrations = new ArrayList<>();
    private List<RunningVm> running = List.of();
    private double now;
    private int spikes;

    /**
     * @param strategy   null when the policy never migrates: spikes are still simulated,
     *                   VMs just stay where they were placed
     * @param thresholds used to flag spikes that cross a migration threshold
     */
    public SpikeMigrationController(TrackedAllocationPolicy policy,
                                    MigrationStrategy strategy,
                                    Datacenter datacenter,
                                    SpikeSchedule schedule,
                                    SimProperties.Migration thresholds,
                                    List<? extends Vm> vms) {
        this.policy = policy;
        this.strategy = strategy;
        this.datacenter = datacenter;
        this.schedule = schedule;
        this.thresholds = thresholds;
        this.vms = vms;
        vms.forEach(vm -> vm.addOnMigrationFinishListener(info -> onMigrationFinished(vm, info.getHost(), info.getTime())));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void onTick(double now) {
        this.now = now;
        HostLedger ledger = policy.getLedger();
        List<RunningVm> current = new ArrayList<>();

        for (Vm vm : vms) {
            Optional<TrackedAllocationPolicy.Placement> placement = policy.placementOf(vm);
            if (placement.isEmpty() || !isRunning(vm)) continue;

            VmProfile profile = policy.profileOf(vm);
            VmLoad load = schedule.loadAt(profile, now - placement.get().placedAt());
            ledger.setCurrentDemand(profile, load.demandMips());
            current.add(new RunningVm(vm, profile, load, placement.get()));

            if (load.spiking() != spiking.getOrDefault(vm, false)) {
                spiking.put(vm, load.spiking());
                if (load.spiking()) spikes++;
                Host host = vm.getHost();
                listener.onSpike(new SpikeEvent(profile.vmId(), vm.getId(), load.spiking(),
                        load.cpuPercent(), load.ramPercent(), load.demandMips(),
                        profile.isSpiky() && ZoneSpikeMigration.crossedThresholds(thresholds, load) != null,
                        host.getId(), policy.zoneOf(host), now));
            }
        }
        running = current;

        if (strategy != null) strategy.onTick(now, this);
    }

    // ─── API for migration strategies ────────────────────────────────────────

    public List<RunningVm> runningVms() {
        return running;
    }

    public boolean isInFlight(Vm vm) {
        return inFlight.containsKey(vm);
    }

    /** Hosts receiving at least one in-flight migration */
    public Set<Host> migrationTargets() {
        Set<Host> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        inFlight.values().forEach(f -> targets.add(f.to()));
        return targets;
    }

    /** CPU (MIPS) promised to in-flight migrations, per target host */
    public Map<Host, Double> reservedMips() {
        Map<Host, Double> reserved = new IdentityHashMap<>();
        inFlight.values().forEach(f -> reserved.merge(f.to(), f.reservedMips(), Double::sum));
        return reserved;
    }

    /**
     * Starts a live migration of a running VM.
     *
     * @param reservedMips CPU to hold on the target until the migration completes
     */
    public void migrate(RunningVm vm, Host to, String trigger, String detail, double reservedMips) {
        Host from = vm.vm().getHost();
        inFlight.put(vm.vm(), new InFlight(from, to, trigger, detail, vm.load(), now, reservedMips));
        log.debug("{}: migrating {} Host-{} ({}) -> Host-{} ({}) [{}]", trigger, vm.profile().vmId(),
                from.getId(), policy.zoneOf(from), to.getId(), policy.zoneOf(to), detail);
        datacenter.requestVmMigration(vm.vm(), to);
    }

    // ─── Bookkeeping ─────────────────────────────────────────────────────────

    private void onMigrationFinished(Vm vm, Host target, double time) {
        InFlight f = inFlight.remove(vm);
        if (f == null) return;
        migrationCounts.merge(vm, 1, Integer::sum);
        MigrationEvent event = new MigrationEvent(
                vm.getDescription(), vm.getId(),
                f.from().getId(), "Host-" + f.from().getId(), policy.zoneOf(f.from()),
                target.getId(), "Host-" + target.getId(), policy.zoneOf(target),
                f.trigger(), f.detail(),
                f.load().cpuPercent(), f.load().ramPercent(), f.load().demandMips(),
                f.requestedAt(), time);
        migrations.add(event);
        listener.onMigration(event);
    }

    /** Created, on a host and still executing its cloudlet */
    private static boolean isRunning(Vm vm) {
        return vm.isCreated() && vm.getHost() != Host.NULL && !vm.getCloudletScheduler().isEmpty();
    }

    public List<MigrationEvent> getMigrations() {
        return List.copyOf(migrations);
    }

    public int migrationCount(Vm vm) {
        return migrationCounts.getOrDefault(vm, 0);
    }

    public int getSpikes() {
        return spikes;
    }
}
