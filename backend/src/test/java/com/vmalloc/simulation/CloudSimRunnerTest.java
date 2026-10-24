package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;
import com.vmalloc.config.SimProperties.PolicyType;
import com.vmalloc.model.*;
import com.vmalloc.service.VmClassifierClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Scenario tests for the zoned placement + spike migration design.
 * Part of the deferred validation suite (excluded from the default build).
 * Run with: mvn test -Dtest.excludedGroups= -Dgroups=validation
 */
@Tag("validation")
class CloudSimRunnerTest {

    // Form defaults: 1000 MIPS × 4 cores, 8 GB
    private static VmRequest stable(int i, long duration) { return vm(i, 15, 30, 25, 8192, duration); }
    private static VmRequest spiky(int i, long duration)  { return vm(i, 5, 90, 25, 8192, duration); }

    private static VmRequest vm(int i, double min, double max, double avg, long ram, long duration) {
        return new VmRequest("vm-%03d".formatted(i), min, max, avg, 1000, 4, ram, 1000, duration);
    }

    private static SimulationResult run(SimProperties props, List<VmRequest> requests) {
        return new CloudSimRunner(props).run(requests, new VmClassifierClient().classifyBatch(requests));
    }

    private static Map<String, VmPlacementResult> byVm(SimulationResult r) {
        Map<String, VmPlacementResult> m = new HashMap<>();
        r.placements().forEach(p -> m.put(p.vmId(), p));
        return m;
    }

    private static HostMetric host(SimulationResult r, long id) {
        return r.hostMetrics().stream().filter(h -> h.hostId() == id).findFirst().orElseThrow();
    }

    @Test
    void everyVmStartsInTheEcoZoneWithOneSpikyVmPerHost() {
        var r = run(new SimProperties(), List.of(stable(0, 10), stable(1, 10), spiky(2, 10), spiky(3, 10)));
        assertThat(r.placements()).allMatch(p -> p.zone() == Zone.ECO);
        assertThat(r.hostMetrics()).filteredOn(h -> h.zone() == Zone.ECO).allMatch(h -> h.spikyVms() <= 1);
        assertThat(r.summary().performanceHostsUsed()).isZero();   // no VM ran long enough to spike
    }

    @Test
    void lowDemandVmsAreNotCappedAtOnePerPe() {
        // 8 × 400 MIPS = 3200 ≤ 4000 on one Eco host
        var vms = IntStream.range(0, 8).mapToObj(i -> vm(i, 5, 15, 10, 2048, 60)).toList();
        var r = run(new SimProperties(), vms);
        assertThat(r.summary().hostsUsed()).isEqualTo(1);
        assertThat(r.summary().failedAllocations()).isZero();
    }

    @Test
    void oddSpikyVmMigratesToPerformanceAt15sAndBackAfterItsSpike() {
        var r = run(new SimProperties(), List.of(spiky(1, 30)));
        assertThat(r.migrations()).hasSize(2);
        MigrationEvent up = r.migrations().get(0), down = r.migrations().get(1);
        assertThat(up.toZone()).isEqualTo(Zone.PERFORMANCE);
        assertThat(up.trigger()).isEqualTo(SpikeMigrationController.TRIGGER_SPIKE);
        assertThat(up.requestedAt()).isCloseTo(15, within(1.5));
        assertThat(down.toZone()).isEqualTo(Zone.ECO);
        assertThat(down.toHostId()).isEqualTo(up.fromHostId());     // back to its original host
        assertThat(down.requestedAt()).isCloseTo(20, within(1.5));
    }

    @Test
    void evenSpikyVmSpikesAt20s() {
        var r = run(new SimProperties(), List.of(spiky(1, 12), spiky(2, 30)));
        assertThat(r.migrations()).filteredOn(m -> m.vmId().equals("vm-002")).first()
                .satisfies(m -> assertThat(m.requestedAt()).isCloseTo(20, within(1.5)));
    }

    @Test
    void spikesRepeatWhileTheVmRuns() {
        // odd VM: spikes at 15–20, 35–40, 55–60 → 3 round trips in 60 s
        var r = run(new SimProperties(), List.of(spiky(1, 62)));
        assertThat(r.summary().spikes()).isEqualTo(3);
        assertThat(r.summary().migrations()).isEqualTo(6);
    }

    @Test
    void noMigrationWhenLoadStaysBelowEveryThreshold() {
        // spiky (range 60 > 40, avg < 0.6 max) but peak 60% → CPU 60% < 70%, MIPS 2400 < 3000
        SimProperties props = new SimProperties();
        props.getSpike().setRamSpikePercent(60);
        var r = run(props, List.of(vm(1, 0, 60, 20, 8192, 30)));
        assertThat(r.summary().spikes()).isEqualTo(1);
        assertThat(r.migrations()).isEmpty();
    }

    @Test
    void migrationDisabledLeavesSpikesOnEcoHosts() {
        SimProperties props = new SimProperties();
        props.getMigration().setEnabled(false);
        var r = run(props, List.of(spiky(1, 30), stable(2, 30), stable(3, 30), stable(4, 30)));
        assertThat(r.migrations()).isEmpty();
        assertThat(r.summary().overloadHostSeconds()).isGreaterThan(0);   // 3600 + 3000 > 4000 during the spike
    }

    @Test
    void midsemPolicyNeverMigrates() {
        SimProperties props = new SimProperties();
        props.setPolicy(PolicyType.MIDSEM);
        props.getHost().getPerformance().setCount(0);
        var r = run(props, List.of(spiky(1, 30)));
        assertThat(r.migrations()).isEmpty();
        assertThat(byVm(r).get("vm-001").placementReason()).isEqualTo("SPIKY_FIRST_FIT");
    }

    @Test
    void energyOfOneFullEcoHost() {
        // 4 stable × 1000 MIPS on one Eco host: 250 W × 3600 s = 0.25 kWh, idle hosts off
        var r = run(new SimProperties(), IntStream.range(0, 4).mapToObj(i -> stable(i, 3600)).toList());
        assertThat(r.summary().totalEnergyKWh()).isCloseTo(0.25, within(0.0025));
        assertThat(host(r, 3).energyKWh()).isCloseTo(0.25, within(0.0025));
    }

    @Test
    void allHostsAreReportedInHostOrder() {
        var r = run(new SimProperties(), List.of(stable(0, 10)));
        assertThat(r.hostMetrics()).extracting(HostMetric::hostId)
                .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(r.hostMetrics()).filteredOn(HostMetric::active).hasSize(1);
    }
}
