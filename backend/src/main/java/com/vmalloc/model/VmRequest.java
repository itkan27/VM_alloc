package com.vmalloc.model;

import jakarta.validation.constraints.*;

/**
 * DTO representing an incoming VM placement request.
 *
 * Classification features (fed to MLP):
 *   minCpu, maxCpu, avgCpu  — historical CPU usage percentages (0–100)
 *
 * Simulation parameters (used by CloudSim Plus):
 *   mips, numCores, ram, bandwidth, duration
 */
public record VmRequest(

        @NotBlank(message = "vmId must not be blank")
        String vmId,

        // ── MLP classification features ─────────────────────────────────────
        @DecimalMin("0.0") @DecimalMax("100.0")
        double minCpu,

        @DecimalMin("0.0") @DecimalMax("100.0")
        double maxCpu,

        @DecimalMin("0.0") @DecimalMax("100.0")
        double avgCpu,

        // ── CloudSim Plus simulation parameters ─────────────────────────────
        @Positive(message = "mips must be positive")
        long mips,

        @Min(1) @Max(64)
        int numCores,

        @Positive(message = "ram must be positive (MB)")
        long ram,

        @Positive(message = "bandwidth must be positive (Mbps)")
        long bandwidth,

        @Positive(message = "duration must be positive (seconds)")
        long duration

) {}
