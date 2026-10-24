package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import com.vmalloc.model.MigrationEvent;
import com.vmalloc.model.SpikeEvent;
import com.vmalloc.model.Zone;
import com.vmalloc.simulation.SpikeSchedule.VmLoad;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Drives the spike simulation and the live migrations between zones.
 *
 * Every clock tick, for each running VM:
 *   1. compute its load from the {@link SpikeSchedule} and record it in the ledger
 *   2. report spike start / end
 *   3. for a SPIKY VM (ZONED policy only):
 *        ANY threshold crossed while on an Eco host  → migrate to a Performance host
 *        ALL thresholds below while on a Performance host → migrate back to Eco
 *
 * Migrations use CloudSim's live migration (memory copied over the target host's
 * bandwidth). A VM is not re-evaluated while a migration of it is in flight, and the
 * target's capacity is reserved until the migration completes.
 */
public class SpikeMigrationController {

    private static final Logger log = LoggerFactory.getLogger(SpikeMigrationController.class);

    public static final String TRIGGER_SPIKE = "SPIKE";
    public static final String TRIGGER_SPIKE_ENDED = "SPIKE_ENDED";

    /** Receives spike and migration events (called on the simulation thread) */
    public interface Listener {
        Listener NOOP = new Listener() {
            @Override public void onSpike(SpikeEvent event) {}
            @Override public void onMigration(MigrationEvent event) {}
        };

        void onSpike(SpikeEvent event);

        void onMigration(MigrationEvent event);
    }

    private record InFlight(Host from, Host to, String trigger, String detail,
                            VmLoad load, double requestedAt, double reservedMips) {}

    private final TrackedAllocationPolicy policy;
    /** Null when migration is off: spikes are still simulated, VMs just stay put */
    private final ZonedVmAllocationPolicy migrationPolicy;
    private final Datacenter datacenter;
    private final SpikeSchedule schedule;
    private final SimProperties.Migration thresholds;
    private final List<? extends Vm> vms;
    private Listener listener = Listener.NOOP;

    private final Map<Vm, Boolean> spiking = new IdentityHashMap<>();
    private final Map<Vm, InFlight> inFlight = new IdentityHashMap<>();
    private final Map<Vm, Integer> migrationCounts = new IdentityHashMap<>();
    private final Set<Vm> noTargetReported = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<MigrationEvent> migrations = new ArrayList<>();
    private int spikes;

    public SpikeMigrationController(TrackedAllocationPolicy policy,
                                    ZonedVmAllocationPolicy migrationPolicy,
                                    Datacenter datacenter,
                                    SpikeSchedule schedule,
                                    SimProperties.Migration thresholds,
                                    List<? extends Vm> vms) {
        this.policy = policy;
        this.migrationPolicy = migrationPolicy;
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
        HostLedger ledger = policy.getLedger();
        for (Vm vm : vms) {
            Optional<TrackedAllocationPolicy.Placement> placement = policy.placementOf(vm);
            if (placement.isEmpty() || !isRunning(vm)) continue;

            VmProfile profile = policy.profileOf(vm);
            VmLoad load = schedule.loadAt(profile, now - placement.get().placedAt());
            ledger.setCurrentDemand(profile, load.demandMips());
            String crossed = crossedThresholds(load);

            if (load.spiking() != spiking.getOrDefault(vm, false)) {
                spiking.put(vm, load.spiking());
                if (load.spiking()) spikes++;
                Host host = vm.getHost();
                listener.onSpike(new SpikeEvent(profile.vmId(), vm.getId(), load.spiking(),
                        load.cpuPercent(), load.ramPercent(), load.demandMips(), crossed != null,
                        host.getId(), policy.zoneOf(host), now));
            }

            if (migrationPolicy != null && profile.isSpiky() && !inFlight.containsKey(vm)) {
                maybeMigrate(vm, profile, load, crossed, placement.get().host(), now);
            }
        }
    }

    private void maybeMigrate(Vm vm, VmProfile profile, VmLoad load, String crossed, Host home, double now) {
        Host current = vm.getHost();
        Zone zone = policy.zoneOf(current);

        if (crossed != null && zone == Zone.ECO) {
            Optional<Host> target = migrationPolicy.findPerformanceHost(vm, profile, load.demandMips(), reservedMips());
            if (target.isPresent()) {
                start(vm, current, target.get(), TRIGGER_SPIKE, crossed, load, now, load.demandMips());
            } else if (noTargetReported.add(vm)) {
                log.warn("{} crossed a threshold ({}) but no Performance host has room; it stays on Host-{}.",
                        profile.vmId(), crossed, current.getId());
            }
        } else if (crossed == null && zone == Zone.PERFORMANCE) {
            migrationPolicy.findEcoHost(vm, profile, home, reservedMips())
                    .ifPresent(target -> start(vm, current, target, TRIGGER_SPIKE_ENDED,
                            "all thresholds below", load, now, profile.avgDemandMips()));
        }
    }

    private void start(Vm vm, Host from, Host to, String trigger, String detail,
                       VmLoad load, double now, double reservedMips) {
        inFlight.put(vm, new InFlight(from, to, trigger, detail, load, now, reservedMips));
        noTargetReported.remove(vm);
        log.debug("{}: migrating {} Host-{} ({}) -> Host-{} ({}) [{}]", trigger, vm.getDescription(),
                from.getId(), policy.zoneOf(from), to.getId(), policy.zoneOf(to), detail);
        datacenter.requestVmMigration(vm, to);
    }

    private void onMigrationFinished(Vm vm, Host target, double now) {
        InFlight f = inFlight.remove(vm);
        if (f == null) return;
        migrationCounts.merge(vm, 1, Integer::sum);
        MigrationEvent event = new MigrationEvent(
                vm.getDescription(), vm.getId(),
                f.from().getId(), "Host-" + f.from().getId(), policy.zoneOf(f.from()),
                target.getId(), "Host-" + target.getId(), policy.zoneOf(target),
                f.trigger(), f.detail(),
                f.load().cpuPercent(), f.load().ramPercent(), f.load().demandMips(),
                f.requestedAt(), now);
        migrations.add(event);
        listener.onMigration(event);
    }

    /** Human-readable list of crossed thresholds, or null when none is crossed */
    private String crossedThresholds(VmLoad load) {
        List<String> crossed = new ArrayList<>();
        if (thresholds.getCpuThresholdPercent() > 0 && load.cpuPercent() > thresholds.getCpuThresholdPercent())
            crossed.add("CPU %.0f%% > %.0f%%".formatted(load.cpuPercent(), thresholds.getCpuThresholdPercent()));
        if (thresholds.getRamThresholdPercent() > 0 && load.ramPercent() > thresholds.getRamThresholdPercent())
            crossed.add("RAM %.0f%% > %.0f%%".formatted(load.ramPercent(), thresholds.getRamThresholdPercent()));
        if (thresholds.getMipsThreshold() > 0 && load.demandMips() > thresholds.getMipsThreshold())
            crossed.add("MIPS %.0f > %.0f".formatted(load.demandMips(), thresholds.getMipsThreshold()));
        return crossed.isEmpty() ? null : String.join(", ", crossed);
    }

    private Map<Host, Double> reservedMips() {
        Map<Host, Double> reserved = new IdentityHashMap<>();
        inFlight.values().forEach(f -> reserved.merge(f.to(), f.reservedMips(), Double::sum));
        return reserved;
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
