package com.vmalloc.comparison;

import com.vmalloc.model.VmRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded random VM workloads for policy comparisons. The same seed always produces
 * the same VMs, so every policy (and every rerun) sees an identical batch.
 *
 * SPIKY VMs:  min 0–15%, max 75–100%, avg 10–30% of the way from min to max
 *             (range > 40 and avg < 0.6 × max, so the classifier labels them spiky)
 * STABLE VMs: min 5–30%, max = min + 5–30%, avg 30–70% of the way from min to max
 */
public final class WorkloadGenerator {

    private static final int[] MIPS = {500, 1000, 1500, 2000};
    private static final long[] RAM = {1024, 2048, 4096, 8192};

    private WorkloadGenerator() {}

    public static List<VmRequest> generate(int vmCount, double spikyPercent, long seed,
                                           long minDurationSeconds, long maxDurationSeconds) {
        Random rnd = new Random(seed);
        int spikyCount = (int) Math.round(vmCount * spikyPercent / 100.0);

        // Spread spiky VMs evenly through the batch instead of bunching them
        List<VmRequest> vms = new ArrayList<>(vmCount);
        int spikyMade = 0;
        for (int i = 0; i < vmCount; i++) {
            boolean spiky = spikyMade < spikyCount
                    && (long) (i + 1) * spikyCount / vmCount > spikyMade;
            if (spiky) spikyMade++;

            double min, max, avg;
            if (spiky) {
                min = uniform(rnd, 0, 15);
                max = uniform(rnd, 75, 100);
                avg = min + (max - min) * uniform(rnd, 0.10, 0.30);
            } else {
                min = uniform(rnd, 5, 30);
                max = min + uniform(rnd, 5, 30);
                avg = min + (max - min) * uniform(rnd, 0.30, 0.70);
            }

            vms.add(new VmRequest(
                    "vm-%03d".formatted(i + 1),
                    round1(min), round1(max), round1(avg),
                    MIPS[rnd.nextInt(MIPS.length)],
                    1 + rnd.nextInt(4),
                    RAM[rnd.nextInt(RAM.length)],
                    100 + 100L * rnd.nextInt(10),
                    minDurationSeconds + (long) (rnd.nextDouble() * (maxDurationSeconds - minDurationSeconds))));
        }
        return vms;
    }

    private static double uniform(Random rnd, double lo, double hi) {
        return lo + rnd.nextDouble() * (hi - lo);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
