package com.train.booking.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecomputationRecordDto {
    private UUID id;
    private Long segmentId;
    private UUID disputeId;
    private String previousDecision;
    private String recomputedDecision;
    private Double recomputedConfidence;
    private Instant recomputedAt;
    private Map<String, Object> explanation;
}

