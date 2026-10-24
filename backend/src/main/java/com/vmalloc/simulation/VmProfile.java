package com.vmalloc.simulation;

import com.vmalloc.model.VmRequest;
import com.vmalloc.model.VmType;

/**
 * Everything the allocation policies and the spike simulation need to know about one VM.
 *
 *   capacityMips   = mips × cores
 *   avgDemandMips  = avgCpu% × capacityMips   (sustained load)
 *   peakDemandMips = maxCpu% × capacityMips   (load during a spike)
 *
 * {@code spikeNumber} is the VM's 1-based position among SPIKY VMs in submission
 * order (0 for STABLE VMs); odd and even numbers spike on different schedules.
 */
public record VmProfile(
        String vmId,
        VmType type,
        double capacityMips,
        double avgCpuPercent,
        double maxCpuPercent,
        long ram,
        long bw,
        int spikeNumber
) {

    public static VmProfile of(VmRequest req, VmType type, int spikeNumber) {
        return new VmProfile(
                req.vmId(),
                type,
                (double) req.mips() * req.numCores(),
                req.avgCpu(),
                req.maxCpu(),
                req.ram(),
                req.bandwidth(),
                type == VmType.SPIKY ? spikeNumber : 0);
    }

    public boolean isSpiky() {
        return type == VmType.SPIKY;
    }

    public double avgDemandMips() {
        return capacityMips * avgCpuPercent / 100.0;
    }

    public double peakDemandMips() {
        return capacityMips * maxCpuPercent / 100.0;
    }
}
