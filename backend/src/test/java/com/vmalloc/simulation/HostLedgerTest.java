package com.vmalloc.simulation;

import com.vmalloc.model.VmType;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HostLedgerTest {

    private static Host host() {
        List<Pe> pes = IntStream.range(0, 4).mapToObj(i -> (Pe) new PeSimple(1000)).toList();
        return new HostSimple(8192, 1000, 100_000, pes);
    }

    private final VmProfile spiky = new VmProfile("s", VmType.SPIKY, 1000, 3600, 4096, 500);
    private final VmProfile stable = new VmProfile("t", VmType.STABLE, 1000, 1200, 2048, 200);

    @Test
    void bookTracksAverageAndPeakSafeDemand() {
        Host h = host();
        HostLedger ledger = new HostLedger();
        ledger.book(h, spiky);
        ledger.book(h, stable);

        assertThat(ledger.avgMips(h)).isEqualTo(2000);
        assertThat(ledger.guaranteedMips(h)).isEqualTo(4600);   // spiky peak + stable avg
        assertThat(ledger.spikyVms(h)).isEqualTo(1);
        assertThat(ledger.vms(h)).isEqualTo(2);
        assertThat(ledger.cpuUtilization(h)).isEqualTo(0.5);
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
        assertThat(ledger.avgMips(h)).isZero();
        assertThat(ledger.spikyVms(h)).isZero();
    }

    @Test
    void ramAndBandwidthLimitsAreEnforced() {
        Host h = host();
        HostLedger ledger = new HostLedger();
        ledger.book(h, spiky);
        assertThat(ledger.fitsRamAndBw(h, spiky)).isTrue();    // 8192 MB, 1000 Mbps exactly
        ledger.book(h, spiky);
        assertThat(ledger.fitsRamAndBw(h, stable)).isFalse();
    }

    @Test
    void fitsCpuAllowsExactCapacity() {
        Host h = host();
        assertThat(HostLedger.fitsCpu(h, 3000, 1000)).isTrue();
        assertThat(HostLedger.fitsCpu(h, 3000, 1001)).isFalse();
    }
}
