package com.vmalloc.model;

/**
 * One completed live migration of a VM between hosts.
 *
 * trigger: SPIKE (a migration threshold was crossed → Performance Zone) or
 *          SPIKE_ENDED (all thresholds back below → Eco Zone)
 */
public record MigrationEvent(
        String vmId,
        long cloudSimVmId,
        long fromHostId,
        String fromHostName,
        Zone fromZone,
        long toHostId,
        String toHostName,
        Zone toZone,
        String trigger,

        /** Which thresholds were crossed, e.g. "CPU 90% > 70%, MIPS 3600 > 3000" */
        String detail,

        /** VM load when the migration was requested */
        double cpuPercent,
        double ramPercent,
        double demandMips,

        double requestedAt,
        double completedAt
) {}
