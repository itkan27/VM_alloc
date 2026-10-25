package com.vmalloc.comparison;

import com.vmalloc.config.SimProperties.PolicyType;
import com.vmalloc.model.ClassificationResult;
import com.vmalloc.model.SimulationResult;
import com.vmalloc.model.VmRequest;
import com.vmalloc.model.VmType;
import com.vmalloc.service.VmClassifierClient;
import com.vmalloc.simulation.CloudSimRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Runs several placement policies on the same workload and datacenter and collects
 * their summaries side by side. VMs are classified once; each policy gets its own
 * independent CloudSim simulation (run in parallel), with identical simulated spikes.
 */
@Service
public class ComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ComparisonService.class);

    private final VmClassifierClient classifierClient;
    private final CloudSimRunner cloudSimRunner;

    public ComparisonService(VmClassifierClient classifierClient, CloudSimRunner cloudSimRunner) {
        this.classifierClient = classifierClient;
        this.cloudSimRunner = cloudSimRunner;
    }

    public ComparisonResult compare(ComparisonRequest request) {
        boolean generated = !request.hasVms();
        List<VmRequest> vms = generated
                ? WorkloadGenerator.generate(request.vmCountOrDefault(), request.spikyPercentOrDefault(),
                        request.seedOrDefault(), request.minDurationOrDefault(), request.maxDurationOrDefault())
                : request.vms();

        CloudSimRunner.RunConfig defaults = cloudSimRunner.defaultConfig();
        int hostCount = generated || request.hostCount() != null ? request.hostCountOrDefault() : defaults.hostCount();
        int perfCount = generated || request.performanceCount() != null
                ? request.performanceCountOrDefault() : defaults.performanceCount();

        Map<String, ClassificationResult> classifications = classifierClient.classifyBatch(vms);
        int spiky = (int) classifications.values().stream().filter(c -> c.vmType() == VmType.SPIKY).count();

        List<PolicyType> policies = request.policiesOrDefault();
        log.info("Comparing {} policies on {} VMs ({} spiky), {} hosts ({} performance).",
                policies.size(), vms.size(), spiky, hostCount, perfCount);

        List<ComparisonResult.PolicyRun> runs = policies.parallelStream()
                .map(policy -> {
                    long start = System.currentTimeMillis();
                    SimulationResult result = cloudSimRunner.run(vms, classifications,
                            new CloudSimRunner.RunConfig(policy, hostCount, perfCount));
                    PolicyInfo info = PolicyInfo.of(policy);
                    return new ComparisonResult.PolicyRun(policy, info.label(), info.description(),
                            info.ours(), info.basePaper(), result.summary(), System.currentTimeMillis() - start);
                })
                .toList();

        var scenario = new ComparisonResult.Scenario(
                vms.size(), spiky, vms.size() - spiky, hostCount, Math.min(perfCount, hostCount), generated,
                generated ? request.seedOrDefault() : null,
                generated ? request.minDurationOrDefault() : vms.stream().mapToLong(VmRequest::duration).min().orElse(0),
                generated ? request.maxDurationOrDefault() : vms.stream().mapToLong(VmRequest::duration).max().orElse(0));

        return new ComparisonResult(scenario, runs, PaperReference.TABLE_II, PaperReference.NOTE);
    }

    /** Display name and one-line description of each policy */
    record PolicyInfo(String label, String description, boolean ours, boolean basePaper) {

        static PolicyInfo of(PolicyType policy) {
            return switch (policy) {
                case ZONED -> new PolicyInfo("Ours: Eco/Performance zones",
                        "All VMs start in the Eco Zone (one spiky VM per host); spiky VMs live-migrate to the "
                        + "Performance Zone while they cross a CPU/RAM/MIPS threshold.", true, false);
                case MIDSEM -> new PolicyInfo("Ours (midsem)",
                        "One spiky VM per host, stable VMs best-fit; no zones, no migration.", true, false);
                case ARA -> new PolicyInfo("Base paper: ARA",
                        "Adaptive Resource-Aware placement: multi-resource filter, score = α·CPU + β·RAM + "
                        + "γ·energy efficiency − δ·migration cost, consolidation of underutilised hosts.", false, true);
                case FIRST_FIT -> new PolicyInfo("First Fit",
                        "First host with enough CPU, RAM and bandwidth.", false, false);
                case BEST_FIT -> new PolicyInfo("Best Fit",
                        "Host with the least CPU left that still fits.", false, false);
            };
        }
    }
}
