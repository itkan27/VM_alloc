package com.vmalloc.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Part of the deferred validation suite (excluded from the default build). */
@Tag("validation")
@SpringBootTest(properties = {
        "sim.host.performance.count=2",
        "sim.spike.odd.afterSeconds=12",
        "sim.migration.cpuThresholdPercent=80"
})
class SimPropertiesBindingTest {

    @Autowired
    private SimProperties props;

    @Test
    void bindsApplicationPropertiesAndOverrides() {
        assertThat(props.getPolicy()).isEqualTo(SimProperties.PolicyType.ZONED);
        assertThat(props.getHost().performanceCount()).isEqualTo(2);
        assertThat(props.getHost().getPerformance().getBandwidth()).isEqualTo(40_000);
        assertThat(props.getHost().getEco().getPowerModel().getIdleWatts()).isEqualTo(80);
        assertThat(props.getSpike().getOdd().getAfterSeconds()).isEqualTo(12);
        assertThat(props.getSpike().getEven().getAfterSeconds()).isEqualTo(20);
        assertThat(props.getMigration().getCpuThresholdPercent()).isEqualTo(80);
        assertThat(props.getMigration().getMipsThreshold()).isEqualTo(3000);
    }
}
