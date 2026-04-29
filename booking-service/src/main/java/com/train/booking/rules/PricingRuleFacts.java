package com.train.booking.rules;

import lombok.AllArgsConstructor;
import lombok.Data; 
import lombok.NoArgsConstructor;

import java.math.BigDecimal;


public class PricingRuleFacts {

    /** Trip and demand context. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PricingContext {
        private Long tripId;
        private String fromStation;
        private String toStation;
        private java.time.Instant departureTime;
        private java.time.Instant bookingTime;
        private int totalSeats;
        private int seatsSold;
        private double occupancyPct;   // 0–100 example business rules
        private boolean peakDeparture; // 06:30–09:30 or 16:00–19:00 example business rule 
        private boolean weekendDeparture;
        private long daysUntilDeparture;
    }

    /** A fare tier option. Rules may set available = false. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FareTierOption {
        private String tierName;
        private BigDecimal price;
        private int displayOrder;
        private boolean available; // modified by rules
    }

  
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelectedFare {
        private BigDecimal price;
        private String tierName;
    }
}
