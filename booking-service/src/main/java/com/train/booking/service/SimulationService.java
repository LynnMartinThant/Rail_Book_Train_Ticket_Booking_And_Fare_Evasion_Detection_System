package com.train.booking.service;

import com.train.booking.domain.*;
import com.train.booking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Runs a full journey + fare evasion simulation: one user with ticket (PAID segment, train match, confidence),
 * one user without ticket (PENDING_RESOLUTION / UNPAID_TRAVEL). For demos and testing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationService {

    public static final String SIM_PAID_USER = "sim-paid";
    public static final String SIM_UNPAID_USER = "sim-unpaid";
    private static final double SIM_GPS_ACCURACY_METERS = 10.0;
    private static final int JOURNEY_MINUTES = 25;

    private final TripRepository tripRepository;
    private final TripSeatRepository tripSeatRepository;
    private final ReservationRepository reservationRepository;
    private final GeofenceRepository geofenceRepository;
    private final GeofenceService geofenceService;
    private final LocationService locationService;
    private final BookingService bookingService;
    private final TripSegmentRepository tripSegmentRepository;

    /**
     * Create a CONFIRMED ticket for a user on a trip (demo only). Finds first free seat, reserve → payment → confirm.
     */
    @Transactional
    public Reservation createTicketForUser(String userId, Long tripId) {
        Trip trip = tripRepository.findByIdWithTrain(tripId)
            .orElseThrow(() -> new IllegalArgumentException("Trip not found: " + tripId));

        Long seatId = findFirstFreeSeatId(tripId);
        if (seatId == null) {
            throw new IllegalStateException("No free seat on trip " + tripId + " (" + trip.getFromStation() + " → " + trip.getToStation() + ")");
        }

        List<Reservation> created = bookingService.reserve(userId, tripId, List.of(seatId), null, null);
        Reservation r = created.get(0);
        bookingService.payment(r.getId(), userId, "SIM-DEMO");
        return bookingService.confirm(r.getId(), userId);
    }

    /**
     * Run full simulation: pick a trip with geofences, create ticket for sim-paid, simulate both journeys with accuracy.
     * Returns summary of segments (fare status, confidence, matched trip).
     */
    @Transactional
    public SimulationResult runFullSimulation(Long tripIdOverride) {
        Trip trip = tripIdOverride != null
            ? tripRepository.findByIdWithTrain(tripIdOverride).orElseThrow(() -> new IllegalArgumentException("Trip not found: " + tripIdOverride))
            : pickTripForSimulation();

        String fromStation = trip.getFromStation();
        String toStation = trip.getToStation();

        Geofence originGeofence = requireGeofenceForStation(fromStation);
        Geofence destGeofence = requireGeofenceForStation(toStation);

        // Base time: trip departs in a few minutes so segment start falls in ±5 min window
        Instant base = trip.getDepartureTime().minus(2, ChronoUnit.MINUTES);
        Instant enterOrigin = base;
        Instant exitOrigin = base.plus(1, ChronoUnit.MINUTES);
        Instant enterDest = exitOrigin.plus(JOURNEY_MINUTES, ChronoUnit.MINUTES);

        // Create a ticket for the paid user. If seat conflicts occur due to prior demo runs, retry by picking a new trip.
        try {
            createTicketForUser(SIM_PAID_USER, trip.getId());
        } catch (RuntimeException ex) {
            log.warn("Simulation booking failed for trip {} ({} → {}): {}. Retrying with a different trip.",
                trip.getId(), fromStation, toStation, ex.getMessage());
            Trip retryTrip = pickTripForSimulation();
            trip = retryTrip;
            fromStation = trip.getFromStation();
            toStation = trip.getToStation();
            originGeofence = requireGeofenceForStation(fromStation);
            destGeofence = requireGeofenceForStation(toStation);
            base = trip.getDepartureTime().minus(2, ChronoUnit.MINUTES);
            enterOrigin = base;
            exitOrigin = base.plus(1, ChronoUnit.MINUTES);
            enterDest = exitOrigin.plus(JOURNEY_MINUTES, ChronoUnit.MINUTES);
            createTicketForUser(SIM_PAID_USER, trip.getId());
        }

        geofenceService.recordEvent(SIM_PAID_USER, originGeofence.getId(), GeofenceEvent.EventType.ENTERED, enterOrigin, SIM_GPS_ACCURACY_METERS);
        geofenceService.recordEvent(SIM_PAID_USER, originGeofence.getId(), GeofenceEvent.EventType.EXITED, exitOrigin, SIM_GPS_ACCURACY_METERS);
        geofenceService.recordEvent(SIM_PAID_USER, destGeofence.getId(), GeofenceEvent.EventType.ENTERED, enterDest, SIM_GPS_ACCURACY_METERS);
        locationService.saveUserLocationOnly(SIM_PAID_USER, destGeofence.getLatitude(), destGeofence.getLongitude());

        Instant enterOriginUnpaid = enterDest.plus(5, ChronoUnit.MINUTES);
        Instant exitOriginUnpaid = enterOriginUnpaid.plus(1, ChronoUnit.MINUTES);
        Instant enterDestUnpaid = exitOriginUnpaid.plus(JOURNEY_MINUTES, ChronoUnit.MINUTES);

        geofenceService.recordEvent(SIM_UNPAID_USER, destGeofence.getId(), GeofenceEvent.EventType.ENTERED, enterOriginUnpaid, SIM_GPS_ACCURACY_METERS);
        geofenceService.recordEvent(SIM_UNPAID_USER, destGeofence.getId(), GeofenceEvent.EventType.EXITED, exitOriginUnpaid, SIM_GPS_ACCURACY_METERS);
        geofenceService.recordEvent(SIM_UNPAID_USER, originGeofence.getId(), GeofenceEvent.EventType.ENTERED, enterDestUnpaid, SIM_GPS_ACCURACY_METERS);
        locationService.saveUserLocationOnly(SIM_UNPAID_USER, originGeofence.getLatitude(), originGeofence.getLongitude());

        List<TripSegment> segmentsPaid = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(SIM_PAID_USER, PageRequest.of(0, 10));
        List<TripSegment> segmentsUnpaid = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(SIM_UNPAID_USER, PageRequest.of(0, 10));

        List<SimulationResult.SegmentSummary> summaries = new ArrayList<>();
        for (TripSegment s : segmentsPaid) {
            summaries.add(toSummary(s, SIM_PAID_USER, true));
        }
        for (TripSegment s : segmentsUnpaid) {
            summaries.add(toSummary(s, SIM_UNPAID_USER, false));
        }
        summaries.sort(Comparator.comparing(SimulationResult.SegmentSummary::getCreatedAt).reversed());

        String message = String.format("Simulation complete: %s → %s. User '%s' had ticket (PAID, confidence %s); user '%s' no ticket (%s).",
            fromStation, toStation, SIM_PAID_USER,
            segmentsPaid.isEmpty() ? "n/a" : segmentsPaid.get(0).getConfidenceScore(),
            SIM_UNPAID_USER,
            segmentsUnpaid.isEmpty() ? "n/a" : segmentsUnpaid.get(0).getFareStatus());

        return SimulationResult.builder()
            .tripId(trip.getId())
            .fromStation(fromStation)
            .toStation(toStation)
            .departureTime(trip.getDepartureTime())
            .paidUserId(SIM_PAID_USER)
            .unpaidUserId(SIM_UNPAID_USER)
            .segments(summaries)
            .message(message)
            .build();
    }

    private Trip pickTripForSimulation() {
        List<Trip> trips = tripRepository.findAllWithTrain();
        if (trips.isEmpty()) throw new IllegalStateException("No trips in database");
        // Pick the first trip that has both geofences and at least one free seat.
        for (Trip t : trips) {
            boolean hasOrigin = !geofenceRepository.findAllByStationName(t.getFromStation()).isEmpty();
            boolean hasDest = !geofenceRepository.findAllByStationName(t.getToStation()).isEmpty();
            if (!hasOrigin || !hasDest) continue;
            Long freeSeat = findFirstFreeSeatId(t.getId());
            if (freeSeat != null) return t;
        }
        // Fallback to first trip (may still fail; caller will surface error)
        return trips.get(0);
    }

    private Geofence requireGeofenceForStation(String stationName) {
        List<Geofence> list = geofenceRepository.findAllByStationName(stationName);
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException("No geofence for station: " + stationName);
        }
        return list.get(0);
    }

    private Long findFirstFreeSeatId(Long tripId) {
        List<TripSeat> seats = tripSeatRepository.findByTripId(tripId);
        Instant now = Instant.now();
        List<ReservationStatus> active = List.of(ReservationStatus.RESERVED, ReservationStatus.PENDING_PAYMENT,
            ReservationStatus.PAYMENT_PROCESSING, ReservationStatus.PAID, ReservationStatus.CONFIRMED);
        for (TripSeat ts : seats) {
            if (reservationRepository.findActiveByTripSeatId(ts.getId(), active, now).isEmpty()) {
                return ts.getSeat().getId();
            }
        }
        return null;
    }

    private static SimulationResult.SegmentSummary toSummary(TripSegment s, String userId, boolean hadTicket) {
        return SimulationResult.SegmentSummary.builder()
            .segmentId(s.getId())
            .userId(userId)
            .originStation(s.getOriginStation())
            .destinationStation(s.getDestinationStation())
            .fareStatus(s.getFareStatus().name())
            .confidenceScore(s.getConfidenceScore() != null ? s.getConfidenceScore().doubleValue() : null)
            .matchedTripId(s.getMatchedTripId())
            .hadTicket(hadTicket)
            .createdAt(s.getCreatedAt())
            .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class SimulationResult {
        private Long tripId;
        private String fromStation;
        private String toStation;
        private Instant departureTime;
        private String paidUserId;
        private String unpaidUserId;
        private List<SegmentSummary> segments;
        private String message;

        @lombok.Data
        @lombok.Builder
        public static class SegmentSummary {
            private Long segmentId;
            private String userId;
            private String originStation;
            private String destinationStation;
            private String fareStatus;
            private Double confidenceScore;
            private Long matchedTripId;
            private boolean hadTicket;
            private Instant createdAt;
        }
    }
}
