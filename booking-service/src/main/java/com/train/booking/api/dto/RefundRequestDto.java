package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class RefundRequestDto {
    private Long id;
    private Long reservationId;
    private String userId;
    private Instant requestedAt;
    private String status;
    private Instant processedAt;
    private String processedBy;
    private ReservationDto reservation;
}
