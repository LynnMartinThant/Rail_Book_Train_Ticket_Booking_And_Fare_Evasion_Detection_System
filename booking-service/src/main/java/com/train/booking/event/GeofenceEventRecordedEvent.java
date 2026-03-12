package com.train.booking.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * Published when a geofence event is recorded (ENTERED/EXITED).
 * Consumed by pipeline: Drools (Warning) → Travel Detection → Violation Detection → Penalty.
 */
@Getter
public class GeofenceEventRecordedEvent extends ApplicationEvent {

    private final String userId;
    private final Long geofenceId;
    private final String eventType;
    private final String stationName;
    private final Instant createdAt;

    public GeofenceEventRecordedEvent(Object source, String userId, Long geofenceId, String eventType, String stationName, Instant createdAt) {
        super(source);
        this.userId = userId;
        this.geofenceId = geofenceId;
        this.eventType = eventType;
        this.stationName = stationName;
        this.createdAt = createdAt;
    }
}
