package com.vmalloc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datacenter and allocation settings bound from the {@code sim.*} keys in
 * application.properties.
 *
 * The datacenter is split into two zones:
 *   Performance Zone — the first {@code sim.host.performance.count} hosts, high-MIPS,
 *                      reserved for SPIKY VMs with full peak headroom
 *   Eco Zone         — the remaining hosts, standard MIPS, densely packed with STABLE VMs
 *
 * Set {@code sim.host.performance.count=0} for a single homogeneous (all Eco) datacenter.
 */
@ConfigurationProperties(prefix = "sim")
public class SimProperties {

    public enum PolicyType { ZONED, MIDSEM }

    /** POWER_OFF: hosts without VMs draw no power. KEEP_IDLE: every host draws at least idle power. */
    public enum IdlePolicy { POWER_OFF, KEEP_IDLE }

    /** FIRST_FIT packs spiky VMs onto as few Performance hosts as possible; WORST_FIT spreads them. */
    public enum PerformanceStrategy { FIRST_FIT, WORST_FIT }

    private PolicyType policy = PolicyType.ZONED;
    private final Hosts host = new Hosts();
    private final ZoneRules zone = new ZoneRules();

    public PolicyType getPolicy() { return policy; }
    public void setPolicy(PolicyType policy) { this.policy = policy; }
    public Hosts getHost() { return host; }
    public ZoneRules getZone() { return zone; }

    // ── sim.host.* ───────────────────────────────────────────────────────────

    public static class Hosts {
        /** Total hosts across both zones */
        private int count = 10;
        private long bandwidth = 10_000;
        private long storage = 1_000_000;
        private IdlePolicy idlePolicy = IdlePolicy.POWER_OFF;
        private final HostClass performance = new HostClass(3, 4, 5000, 65_536, 400, 150);
        private final HostClass eco = new HostClass(0, 4, 1000, 32_768, 250, 80);

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public long getBandwidth() { return bandwidth; }
        public void setBandwidth(long bandwidth) { this.bandwidth = bandwidth; }
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
        private final PowerModel powerModel = new PowerModel();

        HostClass(int count, int pes, long mips, long ram, double maxWatts, double idleWatts) {
            this.count = count;
            this.pes = pes;
            this.mips = mips;
            this.ram = ram;
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

    // ── sim.zone.* ───────────────────────────────────────────────────────────

    public static class ZoneRules {
        private final PerformanceRules performance = new PerformanceRules();
        /** Place a SPIKY VM on an Eco host (alone, with peak headroom) when the Performance Zone is full */
        private boolean allowSpikyOverflowToEco = true;
        /** Place a STABLE VM on a Performance host when the Eco Zone is full, if spiky peak headroom survives */
        private boolean allowStableOverflowToPerf = true;

        public PerformanceRules getPerformance() { return performance; }
        public boolean isAllowSpikyOverflowToEco() { return allowSpikyOverflowToEco; }
        public void setAllowSpikyOverflowToEco(boolean v) { this.allowSpikyOverflowToEco = v; }
        public boolean isAllowStableOverflowToPerf() { return allowStableOverflowToPerf; }
        public void setAllowStableOverflowToPerf(boolean v) { this.allowStableOverflowToPerf = v; }
    }

    public static class PerformanceRules {
        private PerformanceStrategy strategy = PerformanceStrategy.FIRST_FIT;
        /** Upper bound on spiky VMs per Performance host; 0 = limited only by peak headroom */
        private int maxSpikyPerHost = 0;

        public PerformanceStrategy getStrategy() { return strategy; }
        public void setStrategy(PerformanceStrategy strategy) { this.strategy = strategy; }
        public int getMaxSpikyPerHost() { return maxSpikyPerHost; }
        public void setMaxSpikyPerHost(int maxSpikyPerHost) { this.maxSpikyPerHost = maxSpikyPerHost; }
    }
}
