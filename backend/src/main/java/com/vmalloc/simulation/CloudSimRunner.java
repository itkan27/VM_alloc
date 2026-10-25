package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import com.vmalloc.model.*;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * CloudSimRunner
 * ──────────────
 * Builds a zoned CloudSim Plus datacenter, places classified VMs in the Eco Zone,
 * simulates the spikes of SPIKY VMs and live-migrates them between zones. Runs either
 *   • a one-shot batch simulation ({@link #run}), or
 *   • a live simulation paced against the wall clock that reports placements, spikes,
 *     migrations and periodic host snapshots to a {@link SimulationObserver} ({@link #runLive}).
 *
 * Datacenter layout comes from {@link SimProperties} (sim.* keys):
 *   hosts 0 … performance.count-1  → Performance Zone (empty until a VM spikes)
 *   remaining hosts                → Eco Zone
 */
@Component
public class CloudSimRunner {

    private static final Logger log = LoggerFactory.getLogger(CloudSimRunner.class);

    /** Simulated seconds advanced per step of a live run */
    private static final double LIVE_STEP_SECONDS = 1.0;

    /** Minimum wall-clock gap between two host-update snapshots of a live run */
    private static final long HOST_UPDATE_INTERVAL_MS = 250;

    private final SimProperties props;

    public CloudSimRunner(SimProperties props) {
        this.props = props;
    }

    /** Live-run pacing: simulated seconds per wall second, and simulated seconds between VM arrivals */
    public record LiveOptions(double timeScale, double arrivalGapSeconds) {}

    /** Policy and datacenter size for one run; defaults come from application.properties */
    public record RunConfig(SimProperties.PolicyType policy, int hostCount, int performanceCount) {

        public RunConfig {
            hostCount = Math.max(1, hostCount);
            performanceCount = Math.max(0, Math.min(performanceCount, hostCount));
        }
    }

    /** The run configuration from application.properties */
    public RunConfig defaultConfig() {
        return new RunConfig(props.getPolicy(), props.getHost().getCount(), props.getHost().performanceCount());
    }

    /** Everything one simulation run needs, built by {@link #prepare} */
    private record SimRun(
            CloudSimPlus simulation,
            TrackedAllocationPolicy policy,
            List<Host> hosts,
            HostMonitor monitor,
            SpikeMigrationController controller,
            List<VmRequest> requests,
            List<ClassificationResult> classifications,
            List<VmSimple> vms,
            List<VmProfile> profiles
    ) {}

    /**
     * Runs a batch simulation synchronously: all VMs arrive at time 0.
     *
     * @param requests        ordered list of VM placement requests
     * @param classifications map from vmId → ClassificationResult
     * @return complete simulation result ready for the frontend
     */
    public SimulationResult run(
            List<VmRequest> requests,
            Map<String, ClassificationResult> classifications) {
        return run(requests, classifications, defaultConfig());
    }

    /**
     * Runs a batch simulation with an explicit policy and datacenter size (used by the
     * policy comparison). Safe to call from several threads at once.
     */
    public SimulationResult run(
            List<VmRequest> requests,
            Map<String, ClassificationResult> classifications,
            RunConfig config) {

        SimRun run = prepare(requests, classifications, 0.0, config);
        run.simulation().start();
        log.info("Simulation finished. Clock: {} seconds, {} migrations.",
                run.simulation().clock(), run.controller().getMigrations().size());
        return buildResult(run);
    }

    /**
     * Runs a live simulation on the calling thread. VMs arrive {@code arrivalGapSeconds}
     * apart and simulated time advances at {@code timeScale} × wall-clock speed.
     *
     * @param stopRequested polled every step; returning true ends the run early
     * @return the result at the moment the run finished or was stopped
     */
    public SimulationResult runLive(
            List<VmRequest> requests,
            Map<String, ClassificationResult> classifications,
            LiveOptions options,
            SimulationObserver observer,
            BooleanSupplier stopRequested) {

        SimRun run = prepare(requests, classifications, options.arrivalGapSeconds(), defaultConfig());
        CloudSimPlus simulation = run.simulation();
        run.controller().setListener(observer);

        Map<Vm, Integer> indexOf = new IdentityHashMap<>();
        for (int i = 0; i < run.vms().size(); i++) indexOf.put(run.vms().get(i), i);

        run.policy().setPlacementListener((vm, profile, placement) -> {
            ClassificationResult cls = run.classifications().get(indexOf.get(vm));
            Host host = placement.host();
            observer.onPlacement(new PlacementEvent(
                    profile.vmId(), vm.getId(), cls.vmType(), cls.confidence(),
                    host.getId(), "Host-" + host.getId(), placement.zone(), placement.reason(),
                    profile.avgDemandMips(), profile.peakDemandMips(), 0, true,
                    snapshot(run, host), simulation.clock()));
        });

        // CloudSim reports a failure on every broker retry; stream only the first one
        Set<Vm> reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        for (VmSimple vm : run.vms()) {
            vm.addOnCreationFailureListener(info -> {
                if (!reportedFailures.add(vm)) return;
                int i = indexOf.get(vm);
                VmProfile p = run.profiles().get(i);
                ClassificationResult cls = run.classifications().get(i);
                observer.onPlacementFailed(new PlacementFailedEvent(
                        p.vmId(), vm.getId(), cls.vmType(), cls.confidence(),
                        p.avgDemandMips(), p.peakDemandMips(), simulation.clock()));
            });
        }

        observer.onInit(snapshots(run));

        long wallMsPerStep = Math.round(1000.0 * LIVE_STEP_SECONDS / options.timeScale());
        long lastUpdate = 0;

        simulation.startSync();
        while (simulation.isRunning()) {
            if (stopRequested.getAsBoolean()) {
                simulation.terminate();
                break;
            }
            simulation.runFor(LIVE_STEP_SECONDS);

            long now = System.currentTimeMillis();
            if (now - lastUpdate >= HOST_UPDATE_INTERVAL_MS) {
                observer.onHostUpdate(new HostUpdateEvent(simulation.clock(), snapshots(run)));
                lastUpdate = now;
            }
            if (!sleep(wallMsPerStep)) {
                simulation.terminate();
                break;
            }
        }
        observer.onHostUpdate(new HostUpdateEvent(simulation.clock(), snapshots(run)));
        log.info("Live simulation finished. Clock: {} seconds, {} migrations.",
                simulation.clock(), run.controller().getMigrations().size());

        return buildResult(run);
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private SimRun prepare(
            List<VmRequest> requests,
            Map<String, ClassificationResult> classifications,
            double arrivalGapSeconds,
            RunConfig config) {

        log.info("Starting CloudSim Plus simulation: {} VMs, {} hosts ({} performance), policy={}.",
                requests.size(), config.hostCount(), config.performanceCount(), config.policy());

        // ── 1. Bootstrap simulation ──────────────────────────────────────────
        var simulation = new CloudSimPlus();

        // ── 2. Build allocation policy & datacenter ──────────────────────────
        TrackedAllocationPolicy policy = createPolicy(config.policy());
        List<Host> hosts = buildHosts(policy, config);
        var datacenter = new DatacenterSimple(simulation, hosts, policy);
        datacenter.setSchedulingInterval(1);
        datacenter.setBandwidthPercentForMigration(props.getMigration().getBandwidthPercent());

        // ── 3. Broker ────────────────────────────────────────────────────────
        // Destroy each VM 1 s after its cloudlet finishes, so its host capacity
        // (and power draw) is released at the right time instead of at the end
        var broker = new DatacenterBrokerSimple(simulation);
        broker.setVmDestructionDelay(1.0);

        // ── 4. Create CloudSim VMs & cloudlets ───────────────────────────────
        // Every CloudSim VM is sized to the average demand the policy budgets, split
        // into PEs small enough for any host's PE grid. CloudSim's reservation then
        // never exceeds what the policy checked. MIPS per PE is rounded down to a
        // whole number because CloudSim truncates VM MIPS, and a cloudlet sized from
        // fractional MIPS never completes. Spikes above the average are simulated by
        // the SpikeMigrationController on top of CloudSim.
        double minPeMips = hosts.stream()
                .flatMap(h -> h.getPeList().stream())
                .mapToDouble(Pe::getCapacity)
                .min().orElse(1.0);
        int minHostPes = hosts.stream()
                .mapToInt(h -> h.getPeList().size())
                .min().orElse(1);

        List<ClassificationResult> classList = new ArrayList<>();
        List<VmSimple> vms = new ArrayList<>();
        List<CloudletSimple> cloudlets = new ArrayList<>();
        List<VmProfile> profiles = new ArrayList<>();
        double lastEventTime = 0;
        int spikyCount = 0;

        for (int i = 0; i < requests.size(); i++) {
            VmRequest req = requests.get(i);
            ClassificationResult cls = classificationOf(req, classifications);
            if (cls.vmType() == VmType.SPIKY) spikyCount++;
            VmProfile profile = VmProfile.of(req, cls.vmType(), spikyCount);

            double demand = Math.max(profile.avgDemandMips(), 1.0);
            int vmPes = (int) Math.min(minHostPes, Math.max(1, Math.ceil(demand / minPeMips)));
            long mipsPerPe = Math.max(1L, (long) Math.floor(demand / vmPes));

            VmSimple vm = new VmSimple(mipsPerPe, vmPes);
            vm.setRam(req.ram())
              .setBw(req.bandwidth())
              .setSize(10_000)
              .setCloudletScheduler(new CloudletSchedulerTimeShared());

            // Tag the VM description with the user-supplied ID for traceability
            vm.setDescription(req.vmId());
            policy.register(vm, profile);

            double arrival = i * arrivalGapSeconds;
            if (arrival > 0) vm.setSubmissionDelay(arrival);
            lastEventTime = Math.max(lastEventTime, arrival + req.duration());

            // Cloudlet uses the VM's full capacity: runtime = length / mipsPerPe = duration
            long cloudletLength = mipsPerPe * req.duration();
            var cloudlet = new CloudletSimple(cloudletLength, vmPes);
            cloudlet.setUtilizationModelCpu(new UtilizationModelDynamic(1.0))
                    .setUtilizationModelRam(new UtilizationModelDynamic(0.01))
                    .setUtilizationModelBw(new UtilizationModelDynamic(0.01))
                    .setVm(vm);

            classList.add(cls);
            vms.add(vm);
            cloudlets.add(cloudlet);
            profiles.add(profile);
        }

        // ── 5. Spike simulation, migration and host monitoring ───────────────
        var controller = new SpikeMigrationController(policy, migrationStrategy(policy), datacenter,
                new SpikeSchedule(props.getSpike()), props.getMigration(), vms);
        var monitor = new HostMonitor(hosts, policy.getLedger(), props.getHost().getIdlePolicy());

        // Each tick: charge the elapsed interval, update VM loads (and migrate), then
        // capture the new host state. A run that stalls well past its last expected
        // event is terminated instead of blocking the calling thread forever.
        double guardTime = 2 * lastEventTime + 60;
        simulation.addOnClockTickListener(info -> {
            double now = info.getTime();
            monitor.integrate(now);
            controller.onTick(now);
            monitor.sample();
            if (now > guardTime && simulation.isRunning()) {
                log.warn("Simulation passed {} s without finishing; terminating.", guardTime);
                simulation.terminate();
            }
        });

        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);

        return new SimRun(simulation, policy, hosts, monitor, controller,
                requests, classList, vms, profiles);
    }

    // Plain comparisons instead of an enum switch: javac compiles an enum switch into a
    // synthetic CloudSimRunner$1 class that other compilers (e.g. an IDE's) do not
    // generate, so mixed builds failed with NoClassDefFoundError
    private TrackedAllocationPolicy createPolicy(SimProperties.PolicyType type) {
        if (type == SimProperties.PolicyType.MIDSEM) return new MidsemVmAllocationPolicy();
        if (type == SimProperties.PolicyType.ARA) return new AraVmAllocationPolicy(props.getAra());
        if (type == SimProperties.PolicyType.FIRST_FIT) return new FirstFitVmAllocationPolicy();
        if (type == SimProperties.PolicyType.BEST_FIT) return new BestFitVmAllocationPolicy();
        return new ZonedVmAllocationPolicy();
    }

    /** Which VMs move at runtime: spiky VMs between zones (ours), or ARA consolidation (base paper) */
    private MigrationStrategy migrationStrategy(TrackedAllocationPolicy policy) {
        if (policy instanceof ZonedVmAllocationPolicy zoned && props.getMigration().isEnabled()) {
            return new ZoneSpikeMigration(zoned, props.getMigration());
        }
        if (policy instanceof AraVmAllocationPolicy ara) {
            return new AraConsolidation(ara, props.getAra());
        }
        return null;
    }

    private List<Host> buildHosts(TrackedAllocationPolicy policy, RunConfig config) {
        SimProperties.Hosts cfg = props.getHost();
        int perfCount = config.performanceCount();

        List<Host> hosts = new ArrayList<>();
        for (int i = 0; i < config.hostCount(); i++) {
            boolean perf = i < perfCount;
            SimProperties.HostClass spec = perf ? cfg.getPerformance() : cfg.getEco();

            List<Pe> pes = IntStream.range(0, spec.getPes())
                    .mapToObj(j -> (Pe) new PeSimple(spec.getMips()))
                    .toList();
            var host = new HostSimple(spec.getRam(), spec.getBandwidth(), cfg.getStorage(), pes);
            host.setVmScheduler(new VmSchedulerTimeShared());
            host.setPowerModel(new PowerModelHostSimple(
                    spec.getPowerModel().getMaxWatts(), spec.getPowerModel().getIdleWatts()));

            policy.setZone(host, perf ? Zone.PERFORMANCE : Zone.ECO);
            hosts.add(host);
        }
        return hosts;
    }

    private static ClassificationResult classificationOf(
            VmRequest req, Map<String, ClassificationResult> classifications) {
        return classifications.getOrDefault(req.vmId(),
                new ClassificationResult(req.vmId(), VmType.STABLE, 0.5));
    }

    private static boolean sleep(long millis) {
        if (millis <= 0) return true;
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── Live snapshots (from the ledger: VMs currently running) ──────────────

    private static List<HostSnapshot> snapshots(SimRun run) {
        return run.hosts().stream().map(h -> snapshot(run, h)).toList();
    }

    private static HostSnapshot snapshot(SimRun run, Host host) {
        HostLedger ledger = run.policy().getLedger();
        int vms = ledger.vms(host);
        int spiky = ledger.spikyVms(host);
        return new HostSnapshot(
                host.getId(),
                "Host-" + host.getId(),
                run.policy().zoneOf(host),
                vms > 0,
                host.getTotalMipsCapacity(),
                ledger.currentLoad(host),
                vms,
                spiky,
                vms - spiky,
                ledger.vmsOn(host).stream().map(VmProfile::vmId).sorted().toList());
    }

    // ── Results ──────────────────────────────────────────────────────────────

    private SimulationResult buildResult(SimRun run) {
        List<VmRequest> requests = run.requests();
        List<VmSimple> vms = run.vms();
        List<VmProfile> profiles = run.profiles();
        TrackedAllocationPolicy policy = run.policy();
        HostMonitor monitor = run.monitor();
        List<MigrationEvent> migrations = run.controller().getMigrations();

        // ── Per-VM placement results ─────────────────────────────────────────
        List<VmPlacementResult> placements = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            VmSimple vm = vms.get(i);
            VmProfile profile = profiles.get(i);
            ClassificationResult cls = run.classifications().get(i);
            Optional<TrackedAllocationPolicy.Placement> placement = policy.placementOf(vm);
            Host host = placement.map(TrackedAllocationPolicy.Placement::host).orElse(Host.NULL);
            boolean placed = placement.isPresent();

            placements.add(new VmPlacementResult(
                    requests.get(i).vmId(),
                    vm.getId(),
                    cls.vmType(),
                    cls.confidence(),
                    placed ? host.getId() : -1L,
                    placed ? "Host-" + host.getId() : "Unallocated",
                    placement.map(TrackedAllocationPolicy.Placement::zone).orElse(null),
                    placement.map(TrackedAllocationPolicy.Placement::reason).orElse(null),
                    profile.avgDemandMips(),
                    profile.peakDemandMips(),
                    run.controller().migrationCount(vm),
                    placed
            ));
        }

        // ── Per-host metrics (all hosts, in host order) ──────────────────────
        List<HostMetric> hostMetrics = new ArrayList<>();
        for (Host host : run.hosts()) {
            Collection<VmProfile> seen = monitor.seenVms(host);
            int spiky = (int) seen.stream().filter(VmProfile::isSpiky).count();
            int in = (int) migrations.stream().filter(m -> m.toHostId() == host.getId()).count();
            int out = (int) migrations.stream().filter(m -> m.fromHostId() == host.getId()).count();

            hostMetrics.add(new HostMetric(
                    host.getId(),
                    "Host-" + host.getId(),
                    policy.zoneOf(host),
                    !seen.isEmpty(),
                    host.getTotalMipsCapacity(),
                    monitor.avgUtilization(host),
                    monitor.maxLoad(host),
                    seen.size(),
                    spiky,
                    seen.size() - spiky,
                    monitor.activeSeconds(host),
                    monitor.overloadSeconds(host),
                    in,
                    out,
                    monitor.energyKWh(host)
            ));
        }

        // ── Summary ──────────────────────────────────────────────────────────
        List<HostMetric> active = hostMetrics.stream().filter(HostMetric::active).toList();
        int spikyTotal = (int) profiles.stream().filter(VmProfile::isSpiky).count();
        int failed = (int) placements.stream().filter(p -> !p.allocationSuccess()).count();
        int placedVms = requests.size() - failed;
        int violated = monitor.slaViolatedVms();

        SimulationSummary summary = new SimulationSummary(
                requests.size(),
                spikyTotal,
                requests.size() - spikyTotal,
                active.size(),
                (int) active.stream().filter(h -> h.zone() == Zone.PERFORMANCE).count(),
                (int) active.stream().filter(h -> h.zone() == Zone.ECO).count(),
                failed,
                migrations.size(),
                run.controller().getSpikes(),
                hostMetrics.stream().mapToDouble(HostMetric::overloadSeconds).sum(),
                placedVms > 0 ? 100.0 * violated / placedVms : 0.0,
                violated,
                monitor.slaViolationVmSeconds(),
                active.stream().mapToDouble(HostMetric::cpuUtilization).average().orElse(0.0),
                active.stream().mapToDouble(HostMetric::activeSeconds).average().orElse(0.0),
                monitor.totalEnergyKWh(),
                policy.avgPlacementTimeMs(),
                policy.totalPlacementTimeMs()
        );

        return new SimulationResult(placements, hostMetrics, migrations, summary);
    }
}
