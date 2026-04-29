package com.train.booking.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentTransitionDto {
    private UUID id;
    private Long segmentId;
    private String fromState;
    private String toState;
    private String triggerEventType;
    private String reason;
    private Instant occurredAt;
    private String correlationId;
}

