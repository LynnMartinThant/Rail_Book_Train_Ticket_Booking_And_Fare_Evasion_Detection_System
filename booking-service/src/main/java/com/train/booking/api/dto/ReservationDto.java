package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ReservationDto {
    private Long id;
    private String status;
    private BigDecimal amount;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String paymentReference;
    private String paymentGateway;
    private String paymentTransactionId;
    private String currency;
    private List<SeatDto> seats;
    private TripDto trip;
    private String userId;
    /** Journey segment when different from trip (e.g. Meadowhall → Sheffield on a Leeds → Sheffield train). */
    private String journeyFromStation;
    private String journeyToStation;
}
