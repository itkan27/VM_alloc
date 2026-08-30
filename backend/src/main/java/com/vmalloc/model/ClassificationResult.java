package com.vmalloc.model;

/**
 * Classification result received from the Python ML service for a single VM.
 */
public record ClassificationResult(
        String vmId,
        VmType vmType,
        double confidence
) {}
