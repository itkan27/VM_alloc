package com.vmalloc.comparison;

import com.vmalloc.config.SimProperties.PolicyType;
import com.vmalloc.model.VmRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * Request body for a policy comparison.
 *
 * Either pass {@code vms} (e.g. the batch from the Simulation tab) or leave it empty to
 * generate a seeded workload of {@code vmCount} VMs, {@code spikyPercent} of them spiky.
 * Every policy runs on the same VMs, the same datacenter and the same simulated spikes.
 * Defaults mirror the base paper's set-up: 50 hosts, 200 VMs.
 */
public record ComparisonRequest(
        List<@Valid VmRequest> vms,

        @Min(1) @Max(2000) Integer vmCount,
        @DecimalMin("0") @DecimalMax("100") Double spikyPercent,
        @Min(1) @Max(500) Integer hostCount,
        @Min(0) @Max(500) Integer performanceCount,
        Long seed,
        @Min(10) @Max(86400) Long minDurationSeconds,
        @Min(10) @Max(86400) Long maxDurationSeconds,

        /** Policies to run; all of them when empty */
        List<PolicyType> policies
) {
    public static final int DEFAULT_VM_COUNT = 200;
    public static final double DEFAULT_SPIKY_PERCENT = 20;
    public static final int DEFAULT_HOST_COUNT = 50;
    public static final int DEFAULT_PERFORMANCE_COUNT = 5;
    public static final long DEFAULT_SEED = 42;
    public static final long DEFAULT_MIN_DURATION = 300;
    public static final long DEFAULT_MAX_DURATION = 600;

    public boolean hasVms() {
        return vms != null && !vms.isEmpty();
    }

    public int vmCountOrDefault() { return vmCount != null ? vmCount : DEFAULT_VM_COUNT; }
    public double spikyPercentOrDefault() { return spikyPercent != null ? spikyPercent : DEFAULT_SPIKY_PERCENT; }
    public int hostCountOrDefault() { return hostCount != null ? hostCount : DEFAULT_HOST_COUNT; }
    public int performanceCountOrDefault() { return performanceCount != null ? performanceCount : DEFAULT_PERFORMANCE_COUNT; }
    public long seedOrDefault() { return seed != null ? seed : DEFAULT_SEED; }
    public long minDurationOrDefault() { return minDurationSeconds != null ? minDurationSeconds : DEFAULT_MIN_DURATION; }

    public long maxDurationOrDefault() {
        long max = maxDurationSeconds != null ? maxDurationSeconds : DEFAULT_MAX_DURATION;
        return Math.max(max, minDurationOrDefault());
    }

    public List<PolicyType> policiesOrDefault() {
        return policies != null && !policies.isEmpty() ? policies : List.of(PolicyType.values());
    }
}
