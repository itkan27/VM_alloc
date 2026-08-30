package com.vmalloc.simulation;

import com.vmalloc.model.*;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.VmSimple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.IntStream;

/**
 * CloudSimRunner
 * ──────────────
 * Builds a CloudSim Plus environment, submits classified VMs, runs a one-shot
 * batch simulation, and returns structured results.
 *
 * Datacenter defaults (overridable via application.properties):
 *   sim.host.count      = 10
 *   sim.host.pes        = 8     (PEs per host)
 *   sim.host.mips       = 3000  (MIPS per PE)
 *   sim.host.ram        = 32768 (MB)
 *   sim.host.bandwidth  = 10000 (Mbps)
 *   sim.host.storage    = 1000000 (MB)
 */
@Component
public class CloudSimRunner {

    private static final Logger log = LoggerFactory.getLogger(CloudSimRunner.class);

    @Value("${sim.host.count:10}")
    private int hostCount;

    @Value("${sim.host.pes:8}")
    private int hostPes;

    @Value("${sim.host.mips:3000}")
    private long hostMips;

    @Value("${sim.host.ram:32768}")
    private long hostRam;

    @Value("${sim.host.bandwidth:10000}")
    private long hostBandwidth;

    @Value("${sim.host.storage:1000000}")
    private long hostStorage;

    /**
     * Runs the simulation synchronously.
     *
     * @param requests        ordered list of VM placement requests
     * @param classifications map from vmId → ClassificationResult
     * @return complete simulation result ready for the frontend
     */
    public synchronized SimulationResult run(
            List<VmRequest> requests,
            Map<String, ClassificationResult> classifications) {

        log.info("Starting CloudSim Plus simulation with {} VMs on {} hosts.",
                requests.size(), hostCount);

        // ── 1. Bootstrap simulation ──────────────────────────────────────────
        var simulation = new CloudSimPlus();

        // ── 2. Build allocation policy & datacenter ──────────────────────────
        var policy = new CustomVmAllocationPolicy();
        var datacenter = new DatacenterSimple(simulation, buildHosts(), policy);
        datacenter.setSchedulingInterval(1);

        // ── 3. Broker ────────────────────────────────────────────────────────
        var broker = new DatacenterBrokerSimple(simulation);

        // ── 4. Create CloudSim VMs & cloudlets ───────────────────────────────
        List<VmSimple> vms = new ArrayList<>();
        List<CloudletSimple> cloudlets = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            VmRequest req = requests.get(i);
            ClassificationResult cls = classifications.get(req.vmId());

            // ── Create VM in CloudSim ───────────────────────────────────────
            // We request only 1 PE in CloudSim to bypass its internal PE-count
            // check. CloudSim's VmScheduler blocks VMs when total provisioned
            // PEs exceed host PEs, but we manage CPU budgeting ourselves using
            // avg-utilization demand (avgCpu × mips × cores) in our policy.
            VmSimple vm = new VmSimple(req.mips(), 1);  // 1 PE for CloudSim
            vm.setRam(req.ram())
              .setBw(req.bandwidth())
              .setSize(10_000)
              .setCloudletScheduler(new CloudletSchedulerTimeShared());

            // Tag the VM description with the user-supplied ID for traceability
            vm.setDescription(req.vmId());

            vms.add(vm);

            // Register spiky VMs in the allocation policy (by object reference)
            if (cls != null && cls.vmType() == VmType.SPIKY) {
                policy.addSpikyVm(vm);
            }

            // Register expected CPU demand = MIPS × cores × (avgCpu / 100)
            // This is the REAL demand used for placement — our policy enforces:
            //   Σ(vm_demand) on a host ≤ host_capacity (pes × mips)
            double cpuDemandMips = req.mips() * req.numCores() * (req.avgCpu() / 100.0);
            policy.setVmCpuDemand(vm, cpuDemandMips);

            // ── Create Cloudlet ─────────────────────────────────────────────
            // 1 PE, full utilization, length = mips × duration
            // Execution time = length / (mips × 1.0) = duration seconds
            long cloudletLength = req.mips() * req.duration();
            var cloudlet = new CloudletSimple(cloudletLength, 1);  // 1 PE
            cloudlet.setUtilizationModelCpu(new UtilizationModelDynamic(1.0))
                    .setUtilizationModelRam(new UtilizationModelDynamic(0.01))
                    .setUtilizationModelBw(new UtilizationModelDynamic(0.01))
                    .setVm(vm);

            cloudlets.add(cloudlet);
        }

        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);

        // ── 5. Run ───────────────────────────────────────────────────────────
        simulation.start();
        log.info("Simulation finished. Clock: {} seconds.", simulation.clock());

        // ── 6. Collect results ───────────────────────────────────────────────
        return buildResult(requests, classifications, vms, datacenter, simulation.clock());
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private List<HostSimple> buildHosts() {
        List<HostSimple> hosts = new ArrayList<>();
        for (int i = 0; i < hostCount; i++) {
            List<org.cloudsimplus.resources.Pe> pes = IntStream.range(0, hostPes)
                    .mapToObj(j -> (org.cloudsimplus.resources.Pe) new PeSimple(hostMips))
                    .toList();
            var host = new HostSimple(hostRam, hostBandwidth, hostStorage, pes);
            host.setVmScheduler(new VmSchedulerTimeShared());
            hosts.add(host);
        }
        return hosts;
    }

    private SimulationResult buildResult(
            List<VmRequest> requests,
            Map<String, ClassificationResult> classifications,
            List<VmSimple> vms,
            DatacenterSimple datacenter,
            double simClock) {

        // ── Per-VM placement results ─────────────────────────────────────────
        List<VmPlacementResult> placements = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            VmRequest req = requests.get(i);
            VmSimple vm = vms.get(i);
            ClassificationResult cls = classifications.getOrDefault(req.vmId(),
                    new ClassificationResult(req.vmId(), VmType.STABLE, 0.5));

            boolean placed = !vm.getHost().equals(org.cloudsimplus.hosts.Host.NULL);
            long hostId = placed ? vm.getHost().getId() : -1L;
            String hostName = placed ? "Host-" + hostId : "Unallocated";

            placements.add(new VmPlacementResult(
                    req.vmId(),
                    vm.getId(),
                    cls.vmType(),
                    cls.confidence(),
                    hostId,
                    hostName,
                    placed
            ));
        }

        // ── Per-host metrics ─────────────────────────────────────────────────
        List<HostMetric> hostMetrics = new ArrayList<>();
        Set<Long> spikyVmIds = new HashSet<>();
        classifications.values().stream()
                .filter(c -> c.vmType() == VmType.SPIKY)
                .forEach(c -> {
                    vms.stream()
                       .filter(v -> v.getDescription().equals(c.vmId()))
                       .findFirst()
                       .ifPresent(v -> spikyVmIds.add(v.getId()));
                });

        // Group VMs by their assigned host (since host.getVmList() is empty after destruction)
        Map<org.cloudsimplus.hosts.Host, List<VmSimple>> hostToVms = new HashMap<>();
        for (VmSimple vm : vms) {
            org.cloudsimplus.hosts.Host h = vm.getHost();
            if (h != null && h != org.cloudsimplus.hosts.Host.NULL) {
                hostToVms.computeIfAbsent(h, k -> new ArrayList<>()).add(vm);
            }
        }

        for (var entry : hostToVms.entrySet()) {
            org.cloudsimplus.hosts.Host host = entry.getKey();
            List<VmSimple> hostedVms = entry.getValue();

            long spiky = hostedVms.stream()
                    .filter(v -> spikyVmIds.contains(v.getId()))
                    .count();
            long stable = hostedVms.size() - spiky;

            // Calculate expected average CPU utilization based on VM configs
            double totalVmCpuDemand = 0;
            double maxVmDuration = 0;
            for (VmSimple vm : hostedVms) {
                // retrieve the avgCpu from the original request
                VmRequest req = requests.stream()
                        .filter(r -> r.vmId().equals(vm.getDescription()))
                        .findFirst().orElse(null);
                
                if (req != null) {
                    // MIPS * cores * (avgCpu / 100)
                    totalVmCpuDemand += (req.mips() * req.numCores() * (req.avgCpu() / 100.0));
                    maxVmDuration = Math.max(maxVmDuration, req.duration());
                }
            }
            double hostTotalMips = host.getTotalMipsCapacity();
            double avgCpuUtil = Math.min(totalVmCpuDemand / hostTotalMips, 1.0);

            hostMetrics.add(new HostMetric(
                    host.getId(),
                    "Host-" + host.getId(),
                    avgCpuUtil,
                    hostedVms.size(),
                    (int) spiky,
                    (int) stable,
                    maxVmDuration
            ));
        }

        // ── Summary ──────────────────────────────────────────────────────────
        long spikyTotal = classifications.values().stream()
                .filter(c -> c.vmType() == VmType.SPIKY).count();
        long failed = placements.stream().filter(p -> !p.allocationSuccess()).count();
        double avgUtil = hostMetrics.stream()
                .mapToDouble(HostMetric::cpuUtilization)
                .average().orElse(0.0);
        double avgHostDuration = hostMetrics.stream()
                .mapToDouble(HostMetric::hostDurationSeconds)
                .average().orElse(0.0);

        SimulationSummary summary = new SimulationSummary(
                requests.size(),
                (int) spikyTotal,
                (int) (requests.size() - spikyTotal),
                hostMetrics.size(),
                (int) failed,
                avgUtil,
                avgHostDuration
        );

        return new SimulationResult(placements, hostMetrics, summary);
    }
}
