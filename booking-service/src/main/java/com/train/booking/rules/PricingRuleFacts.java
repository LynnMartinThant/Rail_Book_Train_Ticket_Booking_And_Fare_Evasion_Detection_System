package com.train.booking.rules;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Facts for Drools pricing rules. Insert context + available tiers; rules mark tiers
 * unavailable or set result fare.
 */
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
        private double occupancyPct;   // 0–100
        private boolean peakDeparture; // 06:30–09:30 or 16:00–19:00
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

    /** Result: selected fare and tier name. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelectedFare {
        private BigDecimal price;
        private String tierName;
    }
}
