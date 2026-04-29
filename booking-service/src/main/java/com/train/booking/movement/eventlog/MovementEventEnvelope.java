package com.train.booking.movement.eventlog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Event envelope for Kafka/in-process publishing.
 * Mirrors the durable DB event log shape.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovementEventEnvelope {
    private String eventId;
    private String userId;
    private String correlationId;
    private String eventType;
    private Instant occurredAt;
    private Instant recordedAt;
    /** Owning platform layer (e.g. STATION_PROCESSING, FARE_POLICY). */
    private String sourceLayer;
    private Object payload;
}

