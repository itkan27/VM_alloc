package com.vmalloc.comparison;

import java.util.List;

/**
 * Results published in the base paper (Kumar, Jain & Pareek, IMED 2026, Table II):
 * 50 heterogeneous PMs, 200 VMs, PlanetLab traces, 24 h in CloudSim.
 *
 * These come from a different set-up (scale, traces, duration) and are shown for
 * reference next to our own simulated runs, not merged with them.
 */
public final class PaperReference {

    public record Row(
            String algorithm,
            double avgCpuUtilization,
            double slaViolationRate,
            double energyKWh,
            int migrations,
            double placementTimeMs
    ) {}

    public static final List<Row> TABLE_II = List.of(
            new Row("First Fit", 60.2, 9.5, 1234, 22, 45),
            new Row("Best Fit", 68.5, 7.8, 1102, 30, 50),
            new Row("PSO-Based", 76.3, 4.7, 1010, 70, 130),
            new Row("Proposed ARA", 82.4, 3.2, 875, 38, 68)
    );

    public static final String NOTE =
            "Base paper Table II: 50 heterogeneous PMs, 200 VMs, PlanetLab traces, 24-hour CloudSim run. "
            + "Different set-up from our simulation; shown for reference only.";

    private PaperReference() {}
}
