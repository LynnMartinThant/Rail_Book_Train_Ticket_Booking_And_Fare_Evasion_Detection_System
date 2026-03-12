package com.train.booking.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SimulateJourneyRequest {
    @NotNull(message = "userId is required")
    private String userId;

    /** Geofence ID of origin station (optional for legacy Doncaster→Sheffield endpoint). */
    private Long originGeofenceId;

    /** Geofence ID of destination station (optional for legacy endpoint). */
    private Long destinationGeofenceId;

    /** ISO-8601 instant when user enters origin platform (e.g. 2026-03-08T19:45:00Z). */
    @NotNull(message = "enterOriginAt is required")
    private String enterOriginAt;

    /** ISO-8601 instant when user enters destination platform (e.g. 2026-03-08T19:50:00Z). Travel duration = enterDestinationAt - enterOriginAt. */
    @NotNull(message = "enterDestinationAt is required")
    private String enterDestinationAt;
}
