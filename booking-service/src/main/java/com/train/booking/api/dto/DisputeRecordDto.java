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
public class DisputeRecordDto {
    private UUID id;
    private Long segmentId;
    private String userId;
    private String reason;
    private String evidenceReference;
    private String status;
    private Instant submittedAt;
    private Instant decidedAt;
    private String decidedBy;
}

