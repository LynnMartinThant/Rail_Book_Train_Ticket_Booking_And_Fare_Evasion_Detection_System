package com.train.booking.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RecordGeofenceEventRequest {
    @NotNull(message = "userId is required")
    private String userId;
    @NotNull(message = "geofenceId is required")
    private Long geofenceId;
    @NotNull(message = "eventType is required (ENTERED or EXITED)")
    private String eventType; // ENTERED, EXITED
}
