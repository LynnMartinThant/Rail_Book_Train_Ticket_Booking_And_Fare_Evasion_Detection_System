package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class TicketAlertDto {
    private Long id;
    private String userId;
    private Long tripId;
    private String message;
    private Instant createdAt;
    private Instant readAt;
    private String fromStation;
    private String toStation;
    private String trainName;
    private Instant departureTime;
}
