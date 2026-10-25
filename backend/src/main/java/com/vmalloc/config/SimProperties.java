package com.vmalloc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datacenter, spike and migration settings bound from the {@code sim.*} keys in
 * application.properties.
 *
 * The datacenter is split into two decoupled zones:
 *   Eco Zone         — standard hosts; EVERY VM is placed here first (at most one
 *                      spiky VM per host, stable VMs best-fit up to capacity)
 *   Performance Zone — the first {@code sim.host.performance.count} hosts, high-MIPS,
 *                      empty at start; a spiky VM is live-migrated here while its load
 *                      crosses a migration threshold, and back to Eco once it drops
 */
@ConfigurationProperties(prefix = "sim")
public class SimProperties {

    public enum PolicyType { ZONED, MIDSEM }

    /** POWER_OFF: hosts without VMs draw no power. KEEP_IDLE: every host draws at least idle power. */
    public enum IdlePolicy { POWER_OFF, KEEP_IDLE }

    private PolicyType policy = PolicyType.ZONED;
    private final Hosts host = new Hosts();
    private final Spike spike = new Spike();
    private final Migration migration = new Migration();

    public PolicyType getPolicy() { return policy; }
    public void setPolicy(PolicyType policy) { this.policy = policy; }
    public Hosts getHost() { return host; }
    public Spike getSpike() { return spike; }
    public Migration getMigration() { return migration; }

    // ── sim.host.* ───────────────────────────────────────────────────────────

    public static class Hosts {
        /** Total hosts across both zones */
        private int count = 10;
        private long storage = 1_000_000;
        private IdlePolicy idlePolicy = IdlePolicy.POWER_OFF;
        private final HostClass performance = new HostClass(3, 4, 5000, 65_536, 40_000, 400, 150);
        private final HostClass eco = new HostClass(0, 4, 1000, 32_768, 10_000, 250, 80);

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public long getStorage() { return storage; }
        public void setStorage(long storage) { this.storage = storage; }
        public IdlePolicy getIdlePolicy() { return idlePolicy; }
        public void setIdlePolicy(IdlePolicy idlePolicy) { this.idlePolicy = idlePolicy; }
        public HostClass getPerformance() { return performance; }
        public HostClass getEco() { return eco; }

        /** Performance hosts actually built (never more than the total host count) */
        public int performanceCount() {
            return Math.max(0, Math.min(performance.getCount(), count));
        }
    }

    /**
     * Hardware and power profile shared by every host of one zone.
     * {@code count} is only read for the Performance Zone; Eco gets the remainder.
     */
    public static class HostClass {
        private int count;
        private int pes;
        /** MIPS per PE */
        private long mips;
        private long ram;
        /** Mbps; also sets how fast VMs live-migrate onto the host */
        private long bandwidth;
        private final PowerModel powerModel = new PowerModel();

        HostClass(int count, int pes, long mips, long ram, long bandwidth, double maxWatts, double idleWatts) {
            this.count = count;
            this.pes = pes;
            this.mips = mips;
            this.ram = ram;
            this.bandwidth = bandwidth;
            powerModel.setMaxWatts(maxWatts);
            powerModel.setIdleWatts(idleWatts);
        }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public int getPes() { return pes; }
        public void setPes(int pes) { this.pes = pes; }
        public long getMips() { return mips; }
        public void setMips(long mips) { this.mips = mips; }
        public long getRam() { return ram; }
        public void setRam(long ram) { this.ram = ram; }
        public long getBandwidth() { return bandwidth; }
        public void setBandwidth(long bandwidth) { this.bandwidth = bandwidth; }
        public PowerModel getPowerModel() { return powerModel; }
    }

    /** Linear power model: P(u) = idle + (max − idle) × u */
    public static class PowerModel {
        private double maxWatts;
        private double idleWatts;

        public double getMaxWatts() { return maxWatts; }
        public void setMaxWatts(double maxWatts) { this.maxWatts = maxWatts; }
        public double getIdleWatts() { return idleWatts; }
        public void setIdleWatts(double idleWatts) { this.idleWatts = idleWatts; }
    }

    // ── sim.spike.* ──────────────────────────────────────────────────────────

    /**
     * Simulated spikes of SPIKY VMs, numbered 1, 2, 3 … in submission order.
     * Odd-numbered VMs spike {@code odd.afterSeconds} after they start, for
     * {@code odd.durationSeconds}; even-numbered ones use the {@code even} window.
     * With {@code repeat} the window recurs (after + duration seconds per cycle).
     *
     * During a spike CPU rises from avgCpu to maxCpu and RAM usage from
     * {@code ramBaselinePercent} to {@code ramSpikePercent} of the VM's RAM.
     */
    public static class Spike {
        private boolean repeat = true;
        private final SpikeWindow odd = new SpikeWindow(15, 5);
        private final SpikeWindow even = new SpikeWindow(20, 5);
        private double ramBaselinePercent = 50;
        private double ramSpikePercent = 90;

        public boolean isRepeat() { return repeat; }
        public void setRepeat(boolean repeat) { this.repeat = repeat; }
        public SpikeWindow getOdd() { return odd; }
        public SpikeWindow getEven() { return even; }
        public double getRamBaselinePercent() { return ramBaselinePercent; }
        public void setRamBaselinePercent(double v) { this.ramBaselinePercent = v; }
        public double getRamSpikePercent() { return ramSpikePercent; }
        public void setRamSpikePercent(double v) { this.ramSpikePercent = v; }
    }

    public static class SpikeWindow {
        private double afterSeconds;
        private double durationSeconds;

        SpikeWindow(double afterSeconds, double durationSeconds) {
            this.afterSeconds = afterSeconds;
            this.durationSeconds = durationSeconds;
        }

        public double getAfterSeconds() { return afterSeconds; }
        public void setAfterSeconds(double afterSeconds) { this.afterSeconds = afterSeconds; }
        public double getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(double durationSeconds) { this.durationSeconds = durationSeconds; }
    }

    // ── sim.migration.* ──────────────────────────────────────────────────────

    /**
     * A spiky VM is migrated to the Performance Zone when ANY threshold is crossed,
     * and back to the Eco Zone when ALL are below again. A threshold of 0 is disabled.
     * Migration runs with the ZONED policy only.
     */
    public static class Migration {
        private boolean enabled = true;
        /** VM CPU utilisation (% of its mips × cores) */
        private double cpuThresholdPercent = 70;
        /** VM RAM usage (% of its allocated RAM) */
        private double ramThresholdPercent = 85;
        /** VM CPU demand in MIPS */
        private double mipsThreshold = 3000;
        /** Share of the target host's bandwidth used to copy VM memory */
        private double bandwidthPercent = 1.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getCpuThresholdPercent() { return cpuThresholdPercent; }
        public void setCpuThresholdPercent(double v) { this.cpuThresholdPercent = v; }
        public double getRamThresholdPercent() { return ramThresholdPercent; }
        public void setRamThresholdPercent(double v) { this.ramThresholdPercent = v; }
        public double getMipsThreshold() { return mipsThreshold; }
        public void setMipsThreshold(double mipsThreshold) { this.mipsThreshold = mipsThreshold; }
        public double getBandwidthPercent() { return bandwidthPercent; }
        public void setBandwidthPercent(double bandwidthPercent) { this.bandwidthPercent = bandwidthPercent; }
    }
}
