package com.train.booking.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Enterprise KPI metrics for admin dashboard (booking, fare evasion, system).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMetricsDto {

    /** Total reservations (all statuses). */
    private long totalReservations;
    /** Count per status: RESERVED, PAID, CONFIRMED, EXPIRED, CANCELLED, REFUNDED. */
    private Map<String, Long> reservationsByStatus;
    /** Total trips. */
    private long totalTrips;
    /** Total trains. */
    private long totalTrains;
    /** Total geofence events (ENTERED/EXITED). */
    private long totalGeofenceEvents;
    /** Total station entry actions (no-ticket-at-entry). */
    private long totalStationEntryActions;
    /** Count per status: PENDING_OPTION, IGNORED, BOUGHT, SCANNED_QR. */
    private Map<String, Long> stationEntryActionsByStatus;
    /** Total trip segments (journey detection). */
    private long totalTripSegments;
    /** Count per fare status: PAID, UNDERPAID, PENDING_RESOLUTION, UNPAID_TRAVEL. */
    private Map<String, Long> tripSegmentsByFareStatus;
    /** Derived: successful bookings (CONFIRMED). */
    private long confirmedBookings;
    /** Derived: expired + cancelled (seat released). */
    private long releasedSeats;
}
