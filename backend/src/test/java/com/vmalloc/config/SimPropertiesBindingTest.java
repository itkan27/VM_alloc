package com.vmalloc.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "sim.host.performance.count=2",
        "sim.zone.performance.strategy=WORST_FIT",
        "sim.host.eco.powerModel.idleWatts=70"
})
class SimPropertiesBindingTest {

    @Autowired
    private SimProperties props;

    @Test
    void bindsApplicationPropertiesAndOverrides() {
        assertThat(props.getPolicy()).isEqualTo(SimProperties.PolicyType.ZONED);
        assertThat(props.getHost().getCount()).isEqualTo(10);
        assertThat(props.getHost().performanceCount()).isEqualTo(2);
        assertThat(props.getHost().getPerformance().getMips()).isEqualTo(5000);
        assertThat(props.getHost().getPerformance().getPowerModel().getMaxWatts()).isEqualTo(400);
        assertThat(props.getHost().getEco().getPowerModel().getIdleWatts()).isEqualTo(70);
        assertThat(props.getZone().getPerformance().getStrategy())
                .isEqualTo(SimProperties.PerformanceStrategy.WORST_FIT);
        assertThat(props.getHost().getIdlePolicy()).isEqualTo(SimProperties.IdlePolicy.POWER_OFF);
    }
}
