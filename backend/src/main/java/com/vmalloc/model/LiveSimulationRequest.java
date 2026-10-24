package com.vmalloc.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * Request body for starting a live (streamed) simulation.
 *
 *   timeScale         — simulated seconds per wall-clock second (default 5, so a
 *                       5 s spike is visible for about a second)
 *   arrivalGapSeconds — simulated seconds between consecutive VM arrivals (default 5)
 */
public record LiveSimulationRequest(

        @NotEmpty(message = "vms must not be empty")
        List<@Valid VmRequest> vms,

        @Positive @DecimalMax("100000")
        Double timeScale,

        @PositiveOrZero @DecimalMax("86400")
        Double arrivalGapSeconds
) {
    public static final double DEFAULT_TIME_SCALE = 5.0;
    public static final double DEFAULT_ARRIVAL_GAP_SECONDS = 5.0;

    public double timeScaleOrDefault() {
        return timeScale != null ? timeScale : DEFAULT_TIME_SCALE;
    }

    public double arrivalGapOrDefault() {
        return arrivalGapSeconds != null ? arrivalGapSeconds : DEFAULT_ARRIVAL_GAP_SECONDS;
    }
}
