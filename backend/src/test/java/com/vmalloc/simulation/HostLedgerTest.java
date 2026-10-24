package com.vmalloc.simulation;

import com.vmalloc.model.VmType;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Part of the deferred validation suite (excluded from the default build). */
@Tag("validation")
class HostLedgerTest {

    private static Host host() {
        List<Pe> pes = IntStream.range(0, 4).mapToObj(i -> (Pe) new PeSimple(1000)).toList();
        return new HostSimple(8192, 1000, 100_000, pes);
    }

    // capacity 4000 MIPS: spiky avg 25% = 1000, peak 90% = 3600
    private final VmProfile spiky = new VmProfile("s", VmType.SPIKY, 4000, 25, 90, 4096, 500, 1);
    private final VmProfile stable = new VmProfile("t", VmType.STABLE, 4000, 25, 30, 2048, 200, 0);

    @Test
    void currentDemandFollowsTheSpike() {
        Host h = host();
        HostLedger ledger = new HostLedger();
        ledger.book(h, spiky);
        ledger.book(h, stable);
        assertThat(ledger.avgMips(h)).isEqualTo(2000);
        assertThat(ledger.currentMips(h)).isEqualTo(2000);

        ledger.setCurrentDemand(spiky, 3600);
        assertThat(ledger.currentMips(h)).isEqualTo(4600);
        assertThat(ledger.currentLoad(h)).isEqualTo(1.15);
        assertThat(ledger.cpuUtilization(h)).isEqualTo(1.0);
    }

    @Test
    void releaseRestoresCapacity() {
        Host h = host();
        HostLedger ledger = new HostLedger();
        ledger.book(h, spiky);
        ledger.book(h, stable);
        ledger.release(h, spiky);
        ledger.release(h, stable);
        assertThat(ledger.isEmpty()).isTrue();
        assertThat(ledger.spikyVms(h)).isZero();
    }

    @Test
    void ramAndBandwidthLimitsAreEnforced() {
        Host h = host();
        HostLedger ledger = new HostLedger();
        ledger.book(h, spiky);
        assertThat(ledger.fitsRamAndBw(h, spiky)).isTrue();    // 8192 MB, 1000 Mbps exactly
    }
}
