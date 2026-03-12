package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class TripSegmentDto {
    private Long id;
    private String passengerId;
    private String originStation;
    private String destinationStation;
    private String originPlatform;
    private String destinationPlatform;
    private Instant segmentStartTime;
    private Instant segmentEndTime;
    private String fareStatus; // PAID, UNDERPAID, PENDING_RESOLUTION, UNPAID_TRAVEL
    private Instant resolutionDeadline;
    private BigDecimal paidFare;
    private BigDecimal additionalFare;
    private BigDecimal penaltyAmount;
    private Instant createdAt;
}
