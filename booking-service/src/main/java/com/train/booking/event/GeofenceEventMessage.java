package com.train.booking.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Payload for event stream (Kafka or in-process). Same shape for both.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeofenceEventMessage {
    private String userId;
    private Long geofenceId;
    private String eventType;  // ENTERED, EXITED
    private String stationName;
    private Instant createdAt;
}
