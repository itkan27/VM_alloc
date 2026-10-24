package com.vmalloc.simulation;

import com.vmalloc.model.VmRequest;
import com.vmalloc.model.VmType;

/**
 * Everything the allocation policies need to know about one VM.
 *
 *   avgDemandMips  = avgCpu% × mips × cores   (sustained load)
 *   peakDemandMips = maxCpu% × mips × cores   (load during a spike)
 */
public record VmProfile(
        String vmId,
        VmType type,
        double avgDemandMips,
        double peakDemandMips,
        long ram,
        long bw
) {

    public static VmProfile of(VmRequest req, VmType type) {
        double totalMips = (double) req.mips() * req.numCores();
        return new VmProfile(
                req.vmId(),
                type,
                totalMips * (req.avgCpu() / 100.0),
                totalMips * (req.maxCpu() / 100.0),
                req.ram(),
                req.bandwidth());
    }

    public boolean isSpiky() {
        return type == VmType.SPIKY;
    }

    /**
     * MIPS a host must hold in reserve for this VM so a spike never overloads it:
     * the peak for SPIKY VMs, the average for STABLE ones.
     */
    public double guaranteedMips() {
        return isSpiky() ? peakDemandMips : avgDemandMips;
    }
}
