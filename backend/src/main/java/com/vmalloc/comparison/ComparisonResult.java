package com.vmalloc.comparison;

import com.vmalloc.config.SimProperties.PolicyType;
import com.vmalloc.model.SimulationSummary;

import java.util.List;

/**
 * Outcome of running several placement policies on the same workload, plus the base
 * paper's published results for reference.
 */
public record ComparisonResult(
        Scenario scenario,
        List<PolicyRun> runs,
        List<PaperReference.Row> paperReference,
        String paperNote
) {

    /** What every policy was run on */
    public record Scenario(
            int vmCount,
            int spikyVms,
            int stableVms,
            int hostCount,
            int performanceCount,
            boolean generated,
            Long seed,
            long minDurationSeconds,
            long maxDurationSeconds
    ) {}

    /** One policy's results; {@code ours} marks this project's policies */
    public record PolicyRun(
            PolicyType policy,
            String label,
            String description,
            boolean ours,
            boolean basePaper,
            SimulationSummary summary,
            long wallTimeMs
    ) {}
}
