package com.train.booking.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditDecisionRecordDto {
    private Long id;
    private String userId;
    private Long segmentId;
    private String decisionType;
    private String correlationId;
    private String sourceLayer;
    private String payloadJson;
    private Instant recordedAt;
}
