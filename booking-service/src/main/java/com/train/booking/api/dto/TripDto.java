package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class TripDto {
    private Long id;
    private String fromStation;
    private String toStation;
    private Instant departureTime;
    /** Platform at fromStation (e.g. 1A, 2B). */
    private String platform;
    private BigDecimal pricePerSeat;
    /** Fare tier from pricing engine (e.g. ADVANCE_1, OFF_PEAK, ANYTIME). */
    private String fareTier;
    private String trainName;
    private String trainCode;
}
