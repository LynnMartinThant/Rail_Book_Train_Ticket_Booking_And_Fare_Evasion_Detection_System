package com.train.booking.rules;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Facts for the layered Drools pipeline: Location Events → Journey Reconstruction → Ticket Verification → Fraud Detection → Risk Scoring.
 */
public final class JourneyDroolsFacts {

    private JourneyDroolsFacts() {}

    /** Layer 1 input: geofence/location event. accuracy in metres; type ENTER/EXIT; stationId = station name. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationEvent {
        private String userId;
        private long timestampEpochMillis;
        private Double accuracy;
        private String stationId;
        private String type; // "ENTER" | "EXIT"
    }

    /** Layer 2: station visit derived from LocationEvent. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StationVisit {
        private String userId;
        private String stationId;
        private String type; // "ENTER" | "EXIT"
        private long timestampEpochMillis;
    }

    /** Layer 2 output: reconstructed journey segment (origin → destination). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Journey {
        private String userId;
        private String originStation;
        private String destinationStation;
        private long startTimeEpochMillis;
        private long endTimeEpochMillis;
    }

    /** Layer 3/4: ticket (reservation) view for rules. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Ticket {
        private String userId;
        private long ticketId;
        private String originStation;
        private String destinationStation;
        private String routeId; // originStation + "-" + destinationStation
        private Instant expiryTime;
        private String status; // "CONFIRMED", "PAID", etc.
    }

    /** Layer 4: one ticket usage by one user (from TripSegment with reservationId). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TicketUsage {
        private long ticketId;
        private String userId;
    }

    /** Layer 4: device login (for multi-device rule). Populated when device tracking is available. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceLogin {
        private String userId;
        private String deviceId;
    }

    /** Layer 3/4 output: fraud alert to be scored and optionally turned into a case. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FraudAlert {
        private String userId;
        private String alertType; // NO_TICKET, EXPIRED_TICKET, OVER_TRAVEL, WRONG_ROUTE, TICKET_SHARING, MULTI_DEVICE_SUSPICIOUS
    }

    /** Layer 5: per-user risk score. Pre-insert one per user before firing; rules add +20 per FraudAlert. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskScore {
        private String userId;
        private int score;
    }

    /** Layer 5 output: high-risk user → create investigation case. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestigationCase {
        private String userId;
    }
}
