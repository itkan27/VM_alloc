package com.vmalloc.controller;

import com.vmalloc.comparison.ComparisonRequest;
import com.vmalloc.comparison.ComparisonResult;
import com.vmalloc.comparison.ComparisonService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * ComparisonController
 * ────────────────────
 * Policy comparison against the base paper.
 *
 * Endpoints:
 *   POST /api/comparison/run → run the selected policies on one workload, return all summaries
 */
@RestController
@RequestMapping("/api/comparison")
public class ComparisonController {

    private final ComparisonService comparisonService;

    public ComparisonController(ComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    /**
     * Request body (every field optional):
     * { "vmCount": 200, "spikyPercent": 20, "hostCount": 50, "performanceCount": 5, "seed": 42,
     *   "minDurationSeconds": 300, "maxDurationSeconds": 600,
     *   "policies": ["ZONED", "ARA", "FIRST_FIT", "BEST_FIT", "MIDSEM"],
     *   "vms": [ ... ]   // optional: compare on these VMs instead of a generated workload
     * }
     */
    @PostMapping("/run")
    public ComparisonResult run(@Valid @RequestBody ComparisonRequest request) {
        return comparisonService.compare(request);
    }
}
