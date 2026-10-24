package com.vmalloc.model;

/** Current state of a live simulation session; result is set once it has finished. */
public record SessionInfo(
        String sessionId,
        SessionStatus status,
        SimulationResult result,
        String error
) {}
