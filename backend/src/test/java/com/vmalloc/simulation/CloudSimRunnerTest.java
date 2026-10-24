package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import com.vmalloc.config.SimProperties.IdlePolicy;
import com.vmalloc.config.SimProperties.PerformanceStrategy;
import com.vmalloc.config.SimProperties.PolicyType;
import com.vmalloc.model.*;
import com.vmalloc.service.VmClassifierClient;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CloudSimRunnerTest {

    // Form defaults used in the midsem report: 1000 MIPS × 4 cores, 8 GB, 3600 s
    private static VmRequest stable(int i) { return vm(i, 15, 30, 25, 8192, 3600); }
    private static VmRequest spiky(int i)  { return vm(i, 5, 90, 25, 8192, 3600); }

    private static VmRequest vm(int i, double min, double max, double avg, long ram, long duration) {
        return new VmRequest("vm-%03d".formatted(i), min, max, avg, 1000, 4, ram, 1000, duration);
    }

    private static SimulationResult run(SimProperties props, List<VmRequest> requests) {
        return new CloudSimRunner(props).run(requests, new VmClassifierClient().classifyBatch(requests));
    }

    /** The midsem setup: 10 identical hosts (4 PE × 1000 MIPS) and the zone-unaware policy */
    private static SimProperties midsem() {
        SimProperties props = new SimProperties();
        props.setPolicy(PolicyType.MIDSEM);
        props.getHost().getPerformance().setCount(0);
        return props;
    }

    private static Map<String, VmPlacementResult> byVm(SimulationResult r) {
        Map<String, VmPlacementResult> m = new HashMap<>();
        r.placements().forEach(p -> m.put(p.vmId(), p));
        return m;
    }

    private static HostMetric host(SimulationResult r, long id) {
        return r.hostMetrics().stream().filter(h -> h.hostId() == id).findFirst().orElseThrow();
    }

    // ─── Midsem policy: report results reproduced, plus Phase 0 fixes ────────

    @Nested
    class MidsemPolicy {

        @Test
        void tc1_fourStableVmsShareOneHost() {
            var r = run(midsem(), IntStream.range(0, 4).mapToObj(CloudSimRunnerTest::stable).toList());
            assertThat(r.summary().hostsUsed()).isEqualTo(1);
            assertThat(r.summary().avgCpuUtilization()).isCloseTo(1.0, within(1e-9));
            assertThat(r.summary().failedAllocations()).isZero();
        }

        @Test
        void tc2_spikyVmsAreIsolatedFromEachOther() {
            var r = run(midsem(), List.of(stable(0), stable(1), spiky(2), spiky(3)));
            assertThat(r.summary().hostsUsed()).isEqualTo(2);
            assertThat(r.summary().avgCpuUtilization()).isCloseTo(0.5, within(1e-9));
            assertThat(r.hostMetrics()).allMatch(h -> h.spikyVms() <= 1);
        }

        @Test
        void tc3_fourSpikyVmsNeedFourHosts() {
            var r = run(midsem(), IntStream.range(0, 4).mapToObj(CloudSimRunnerTest::spiky).toList());
            assertThat(r.summary().hostsUsed()).isEqualTo(4);
            assertThat(r.summary().avgCpuUtilization()).isCloseTo(0.25, within(1e-9));
        }

        @Test
        void lowDemandVmsAreNotCappedAtOnePerPe() {
            // 8 × 400 MIPS = 3200 ≤ 4000: used to need 2 hosts because CloudSim only
            // fitted four 1-PE × 1000-MIPS VMs per host while the policy booked "ghost" capacity
            var vms = IntStream.range(0, 8).mapToObj(i -> vm(i, 5, 15, 10, 2048, 3600)).toList();
            var r = run(midsem(), vms);
            assertThat(r.summary().hostsUsed()).isEqualTo(1);
            assertThat(r.summary().failedAllocations()).isZero();
            assertThat(host(r, 0).cpuUtilization()).isCloseTo(0.8, within(1e-9));
        }

        @Test
        void spikySharingHostWithStableVmsRisksPeakOverload() {
            // 1 spiky (peak 3600) + 3 stable (avg 3000) on one 4000-MIPS host
            var r = run(midsem(), List.of(spiky(0), stable(1), stable(2), stable(3)));
            assertThat(r.summary().hostsUsed()).isEqualTo(1);
            assertThat(r.summary().peakOverloadHosts()).isEqualTo(1);
            assertThat(host(r, 0).peakDemandMips()).isCloseTo(6600, within(1e-6));
        }

        @Test
        void hostActiveTimeIsMeasuredInSeconds() {
            var r = run(midsem(), List.of(vm(0, 15, 30, 25, 8192, 600), vm(1, 15, 30, 25, 8192, 3600)));
            assertThat(r.summary().hostsUsed()).isEqualTo(1);
            assertThat(r.summary().avgHostActiveSeconds()).isCloseTo(3600, within(2.0));
        }

        @Test
        void moreSpikyVmsThanHostsFail() {
            var r = run(midsem(), IntStream.range(0, 11).mapToObj(CloudSimRunnerTest::spiky).toList());
            assertThat(r.summary().hostsUsed()).isEqualTo(10);
            assertThat(r.summary().failedAllocations()).isEqualTo(1);
        }
    }

    // ─── Zoned policy (Phase 1) ──────────────────────────────────────────────

    @Nested
    class ZonedPolicy {

        @Test
        void stableVmsArePackedIntoTheFirstEcoHost() {
            var r = run(new SimProperties(), IntStream.range(0, 4).mapToObj(CloudSimRunnerTest::stable).toList());
            assertThat(r.summary().hostsUsed()).isEqualTo(1);
            assertThat(r.summary().ecoHostsUsed()).isEqualTo(1);
            assertThat(r.placements()).allMatch(p -> p.zone() == Zone.ECO && p.hostId() == 3
                    && "ECO_BEST_FIT".equals(p.placementReason()));
        }

        @Test
        void spikyVmsGoToPerformanceZoneAndStableToEco() {
            var r = run(new SimProperties(), List.of(stable(0), stable(1), spiky(2), spiky(3)));
            var p = byVm(r);
            assertThat(p.get("vm-002").zone()).isEqualTo(Zone.PERFORMANCE);
            assertThat(p.get("vm-003").zone()).isEqualTo(Zone.PERFORMANCE);
            assertThat(p.get("vm-000").zone()).isEqualTo(Zone.ECO);
            assertThat(p.get("vm-001").zone()).isEqualTo(Zone.ECO);
            assertThat(r.summary().performanceHostsUsed()).isEqualTo(1);
            assertThat(r.summary().ecoHostsUsed()).isEqualTo(1);
            assertThat(r.summary().peakOverloadHosts()).isZero();
        }

        @Test
        void fourSpikyVmsShareOnePerformanceHostWithPeakHeadroom() {
            // 4 × 3600 MIPS peak = 14 400 ≤ 20 000
            var r = run(new SimProperties(), IntStream.range(0, 4).mapToObj(CloudSimRunnerTest::spiky).toList());
            assertThat(r.summary().hostsUsed()).isEqualTo(1);
            assertThat(r.summary().failedAllocations()).isZero();
            assertThat(host(r, 0).spikyVms()).isEqualTo(4);
            assertThat(r.placements()).allMatch(p -> "PERF_FIRST_FIT".equals(p.placementReason()));
        }

        @Test
        void spikyVmsOverflowToEcoWhenPerformanceZoneIsFull() {
            // 3 perf hosts × 5 spiky VMs (5 × 3600 = 18 000 ≤ 20 000) = 15, the 16th overflows
            var r = run(new SimProperties(), IntStream.range(0, 16).mapToObj(CloudSimRunnerTest::spiky).toList());
            assertThat(r.summary().failedAllocations()).isZero();
            assertThat(r.summary().performanceHostsUsed()).isEqualTo(3);
            assertThat(r.summary().spikyOverflowCount()).isEqualTo(1);
            assertThat(byVm(r).get("vm-015").zone()).isEqualTo(Zone.ECO);
            assertThat(r.summary().peakOverloadHosts()).isZero();
        }

        @Test
        void spikyOverflowCanBeDisabled() {
            SimProperties props = new SimProperties();
            props.getZone().setAllowSpikyOverflowToEco(false);
            var r = run(props, IntStream.range(0, 16).mapToObj(CloudSimRunnerTest::spiky).toList());
            assertThat(r.summary().failedAllocations()).isEqualTo(1);
        }

        @Test
        void maxSpikyPerHostLimitsPerformanceHosts() {
            SimProperties props = new SimProperties();
            props.getZone().getPerformance().setMaxSpikyPerHost(1);
            var r = run(props, IntStream.range(0, 4).mapToObj(CloudSimRunnerTest::spiky).toList());
            assertThat(r.summary().performanceHostsUsed()).isEqualTo(3);
            assertThat(r.summary().spikyOverflowCount()).isEqualTo(1);
        }

        @Test
        void worstFitSpreadsSpikyVms() {
            SimProperties props = new SimProperties();
            props.getZone().getPerformance().setStrategy(PerformanceStrategy.WORST_FIT);
            var r = run(props, IntStream.range(0, 3).mapToObj(CloudSimRunnerTest::spiky).toList());
            assertThat(r.summary().performanceHostsUsed()).isEqualTo(3);
        }

        @Test
        void stableVmsOverflowToPerformanceZoneWhenEcoIsFull() {
            // 7 eco hosts × 4 stable VMs (4 × 1000 MIPS) fill the Eco Zone
            var r = run(new SimProperties(), IntStream.range(0, 29).mapToObj(CloudSimRunnerTest::stable).toList());
            assertThat(r.summary().ecoHostsUsed()).isEqualTo(7);
            assertThat(byVm(r).get("vm-028").placementReason()).isEqualTo("STABLE_OVERFLOW_PERF");
            assertThat(r.summary().failedAllocations()).isZero();
        }

        @Test
        void energyOfOneFullEcoHost() {
            // 250 W × 3600 s = 0.25 kWh, idle hosts powered off
            var r = run(new SimProperties(), IntStream.range(0, 4).mapToObj(CloudSimRunnerTest::stable).toList());
            assertThat(r.summary().totalEnergyKWh()).isCloseTo(0.25, within(0.0025));
            assertThat(host(r, 3).energyKWh()).isCloseTo(0.25, within(0.0025));
            assertThat(host(r, 0).energyKWh()).isZero();
        }

        @Test
        void energyFollowsVmsFinishingAtDifferentTimes() {
            // One eco host: 2 × 1000 MIPS for 600 s (165 W), then 1 × 1000 MIPS for 3000 s (122.5 W)
            var r = run(new SimProperties(), List.of(vm(0, 15, 30, 25, 8192, 600), vm(1, 15, 30, 25, 8192, 3600)));
            assertThat(r.summary().hostsUsed()).isEqualTo(1);
            assertThat(r.summary().totalEnergyKWh()).isCloseTo((165 * 600 + 122.5 * 3000) / 3.6e6, within(0.0013));
        }

        @Test
        void keepIdleChargesIdlePowerForUnusedHosts() {
            // + (3 × 150 W + 6 × 80 W) × 3600 s = 0.93 kWh
            SimProperties props = new SimProperties();
            props.getHost().setIdlePolicy(IdlePolicy.KEEP_IDLE);
            var r = run(props, IntStream.range(0, 4).mapToObj(CloudSimRunnerTest::stable).toList());
            assertThat(r.summary().totalEnergyKWh()).isCloseTo(1.18, within(0.012));
        }

        @Test
        void allHostsAreReportedInHostOrder() {
            var r = run(new SimProperties(), List.of(stable(0)));
            assertThat(r.hostMetrics()).extracting(HostMetric::hostId)
                    .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
            assertThat(r.hostMetrics()).extracting(HostMetric::zone).containsExactly(
                    Zone.PERFORMANCE, Zone.PERFORMANCE, Zone.PERFORMANCE,
                    Zone.ECO, Zone.ECO, Zone.ECO, Zone.ECO, Zone.ECO, Zone.ECO, Zone.ECO);
            assertThat(r.hostMetrics()).filteredOn(HostMetric::active).hasSize(1);
        }
    }

    // ─── Invariants over random batches ──────────────────────────────────────
    // Slow validation suite, excluded from the default build (see pom.xml).
    // Run with: mvn test -Dtest.excludedGroups= -Dgroups=validation

    @Test
    @Tag("validation")
    void randomBatchesRespectCapacityAndZoneRules() {
        for (long seed = 0; seed < 40; seed++) {
            Random rnd = new Random(seed);
            List<VmRequest> vms = new ArrayList<>();
            int n = 1 + rnd.nextInt(40);
            for (int i = 0; i < n; i++) {
                double min = rnd.nextInt(40), max = min + rnd.nextInt((int) (101 - min));
                double avg = min + (max - min) * rnd.nextDouble();
                vms.add(new VmRequest("vm-%03d".formatted(i), min, max, avg,
                        500 + rnd.nextInt(16) * 100, 1 + rnd.nextInt(8),
                        1024L * (1 + rnd.nextInt(16)), 100 + rnd.nextInt(2000), 600 + rnd.nextInt(7000)));
            }

            for (PolicyType policy : PolicyType.values()) {
                SimProperties props = policy == PolicyType.MIDSEM ? midsem() : new SimProperties();
                var r = run(props, vms);
                String ctx = "seed=" + seed + " policy=" + policy;

                Map<Long, Double> demandPerHost = new HashMap<>();
                for (VmPlacementResult p : r.placements()) {
                    assertThat(p.allocationSuccess()).as(ctx).isEqualTo(p.hostId() >= 0);
                    assertThat(p.zone() != null).as(ctx).isEqualTo(p.allocationSuccess());
                    if (p.allocationSuccess()) demandPerHost.merge(p.hostId(), p.cpuDemandMips(), Double::sum);
                }

                for (HostMetric h : r.hostMetrics()) {
                    double demand = demandPerHost.getOrDefault(h.hostId(), 0.0);
                    assertThat(demand).as(ctx + " host " + h.hostId()).isLessThanOrEqualTo(h.capacityMips() + 1e-6);
                    assertThat(h.active()).as(ctx).isEqualTo(h.totalVms() > 0);
                    if (policy == PolicyType.ZONED) {
                        assertThat(h.peakDemandMips()).as(ctx + " host " + h.hostId())
                                .isLessThanOrEqualTo(h.capacityMips() + 1e-6);
                        if (h.zone() == Zone.ECO) assertThat(h.spikyVms()).as(ctx).isLessThanOrEqualTo(1);
                    } else {
                        assertThat(h.spikyVms()).as(ctx).isLessThanOrEqualTo(1);
                    }
                }

                if (policy == PolicyType.ZONED) {
                    for (VmPlacementResult p : r.placements()) {
                        if (p.vmType() == VmType.SPIKY && p.zone() == Zone.ECO)
                            assertThat(p.placementReason()).as(ctx).isEqualTo("SPIKY_OVERFLOW_ECO");
                        if (p.vmType() == VmType.STABLE && p.zone() == Zone.PERFORMANCE)
                            assertThat(p.placementReason()).as(ctx).isEqualTo("STABLE_OVERFLOW_PERF");
                    }
                    assertThat(r.summary().peakOverloadHosts()).as(ctx).isZero();
                }
            }
        }
    }
}
