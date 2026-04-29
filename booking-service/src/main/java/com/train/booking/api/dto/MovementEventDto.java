package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class MovementEventDto {
    private String eventId;
    private String userId;
    private String correlationId;
    private String eventType;
    private Instant occurredAt;
    private Instant recordedAt;
    /** Owning platform layer (e.g. STATION_PROCESSING, FARE_POLICY). */
    private String sourceLayer;
    private Map<String, Object> payload;
    private Map<String, Object> explanation;
}
