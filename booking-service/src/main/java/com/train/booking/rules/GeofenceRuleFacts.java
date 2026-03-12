package com.train.booking.rules;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Facts inserted into Drools session for geofence automation rules.
 */
public class GeofenceRuleFacts {

    /** Inserted when user enters a station (from geofence event). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserEnteredStation {
        private String userId;
        private String stationName;
        private Long geofenceId;
    }

    /** Inserted from ticket search service: true if user has a CONFIRMED ticket from this station. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TicketSearchResult {
        private String userId;
        private String stationName;
        private boolean hasTicket;
    }

    /** Result: rule fired when no ticket at entry → system should show options (Buy / Ignore / Scan QR). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NoTicketAtEntry {
        private String userId;
        private String stationName;
        private Long geofenceId;
    }
}
