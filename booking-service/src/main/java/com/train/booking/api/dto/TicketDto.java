package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** Admin view of a ticket (CONFIRMED/PAID reservation) for journey matching and reporting. */
@Data
@Builder
public class TicketDto {
    private Long id;
    private String userId;
    private String fromStation;
    private String toStation;
    private Instant departureTime;
    private String trainName;
    private String trainCode;
    private String seatNumber;
    private BigDecimal amount;
    private String status;
}
