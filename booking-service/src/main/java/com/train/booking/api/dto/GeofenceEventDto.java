package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class GeofenceEventDto {
    private Long id;
    private String userId;
    private GeofenceDto geofence;
    private String eventType; // ENTERED, EXITED
    private Instant createdAt;
}
