package com.vmalloc.simulation;

import com.vmalloc.config.SimProperties;

/**
 * Simulated load of a VM over time.
 *
 * STABLE VMs run at avgCpu with baseline RAM usage. SPIKY VMs do too, except inside
 * their spike window, when CPU jumps to maxCpu and RAM usage to the spike level:
 *   odd-numbered spiky VMs  → after {@code odd.afterSeconds}  for {@code odd.durationSeconds}
 *   even-numbered spiky VMs → after {@code even.afterSeconds} for {@code even.durationSeconds}
 * measured from when the VM started; the window recurs when {@code repeat} is on.
 */
public class SpikeSchedule {

    /** Load of one VM at one instant */
    public record VmLoad(boolean spiking, double cpuPercent, double ramPercent, double demandMips) {}

    private final SimProperties.Spike cfg;

    public SpikeSchedule(SimProperties.Spike cfg) {
        this.cfg = cfg;
    }

    public VmLoad loadAt(VmProfile vm, double secondsSinceStart) {
        boolean spiking = isSpiking(vm, secondsSinceStart);
        double cpu = spiking ? vm.maxCpuPercent() : vm.avgCpuPercent();
        double ram = spiking ? cfg.getRamSpikePercent() : cfg.getRamBaselinePercent();
        return new VmLoad(spiking, cpu, ram, vm.capacityMips() * cpu / 100.0);
    }

    public boolean isSpiking(VmProfile vm, double secondsSinceStart) {
        if (!vm.isSpiky() || secondsSinceStart < 0) return false;

        SimProperties.SpikeWindow window = vm.spikeNumber() % 2 == 1 ? cfg.getOdd() : cfg.getEven();
        double after = window.getAfterSeconds();
        double duration = window.getDurationSeconds();
        if (duration <= 0) return false;

        double t = cfg.isRepeat() ? secondsSinceStart % (after + duration) : secondsSinceStart;
        return t >= after && t < after + duration;
    }
}
