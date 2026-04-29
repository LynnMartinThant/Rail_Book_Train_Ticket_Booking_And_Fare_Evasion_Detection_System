package com.train.booking.service;

import com.train.booking.domain.Geofence;
import com.train.booking.domain.GeofenceEvent;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.Trip;
import com.train.booking.domain.TripSeat;
import com.train.booking.domain.TripSegment;
import com.train.booking.movement.eventlog.MovementEventRepository;
import com.train.booking.repository.DisputeRecordRepository;
import com.train.booking.repository.GeofenceEventRepository;
import com.train.booking.repository.GeofenceRepository;
import com.train.booking.repository.RecomputationRecordRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.SegmentTransitionRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.repository.TripSeatRepository;
import com.train.booking.repository.TripSegmentRepository;
import com.train.booking.repository.UserLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DemoScenarioService {

    public static final String USER_VALID = "demo-valid-01";
    public static final String USER_OVER_TRAVEL = "demo-overtravel-01";
    public static final String USER_LOW_QUALITY = "demo-lowquality-01";

    private static final List<String> DEMO_USERS = List.of(USER_VALID, USER_OVER_TRAVEL, USER_LOW_QUALITY);

    private final BookingService bookingService;
    private final GeofenceService geofenceService;
    private final LocationService locationService;
    private final TripRepository tripRepository;
    private final TripSeatRepository tripSeatRepository;
    private final ReservationRepository reservationRepository;
    private final TripSegmentRepository tripSegmentRepository;
    private final SegmentTransitionRepository segmentTransitionRepository;
    private final DisputeRecordRepository disputeRecordRepository;
    private final RecomputationRecordRepository recomputationRecordRepository;
    private final GeofenceEventRepository geofenceEventRepository;
    private final MovementEventRepository movementEventRepository;
    private final UserLocationRepository userLocationRepository;
    private final GeofenceRepository geofenceRepository;

    @Transactional
    public Map<String, Object> resetFixtures() {
        clearAllDemoArtifacts();

        Reservation bookingA = createConfirmedTicket(USER_VALID, "Leeds", "Sheffield", "Leeds", "Sheffield");
        Reservation bookingB = createConfirmedTicket(
            USER_OVER_TRAVEL, "Leeds", "Sheffield", "Leeds", "Meadowhall Interchange"
        );
        Reservation bookingC = createConfirmedTicket(USER_LOW_QUALITY, "Leeds", "Sheffield", "Leeds", "Sheffield");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("message", "Demo fixtures reset and seeded for 3 deterministic scenarios.");
        out.put("bookings", List.of(
            scenarioBooking("VALID", USER_VALID, bookingA),
            scenarioBooking("OVER_TRAVEL", USER_OVER_TRAVEL, bookingB),
            scenarioBooking("LOW_QUALITY", USER_LOW_QUALITY, bookingC)
        ));
        return out;
    }

    @Transactional
    public Map<String, Object> replayScenario(String scenarioRaw) {
        Scenario scenario = Scenario.from(scenarioRaw);
        clearRuntimeArtifactsForUser(scenario.userId);

        Geofence leeds = requireGeofence("Leeds");
        Geofence sheffield = requireGeofence("Sheffield");

        Instant base = Instant.now().minus(15, ChronoUnit.MINUTES);
        if (scenario == Scenario.VALID) {
            String corr = "demo-valid-corr-1";
            geofenceService.recordEvent(scenario.userId, leeds.getId(), GeofenceEvent.EventType.ENTERED, base, 8.0, corr);
            geofenceService.recordEvent(scenario.userId, leeds.getId(), GeofenceEvent.EventType.EXITED, base.plus(60, ChronoUnit.SECONDS), 8.0, corr);
            geofenceService.recordEvent(
                scenario.userId, sheffield.getId(), GeofenceEvent.EventType.ENTERED, base.plus(26, ChronoUnit.MINUTES), 8.0, corr
            );
            locationService.saveUserLocationOnly(scenario.userId, sheffield.getLatitude(), sheffield.getLongitude());
        } else if (scenario == Scenario.OVER_TRAVEL) {
            String corr = "demo-overtravel-corr-1";
            geofenceService.recordEvent(scenario.userId, leeds.getId(), GeofenceEvent.EventType.ENTERED, base, 10.0, corr);
            geofenceService.recordEvent(scenario.userId, leeds.getId(), GeofenceEvent.EventType.EXITED, base.plus(60, ChronoUnit.SECONDS), 10.0, corr);
            geofenceService.recordEvent(
                scenario.userId, sheffield.getId(), GeofenceEvent.EventType.ENTERED, base.plus(28, ChronoUnit.MINUTES), 10.0, corr
            );
            locationService.saveUserLocationOnly(scenario.userId, sheffield.getLatitude(), sheffield.getLongitude());
        } else {
            String corr = "demo-lowquality-corr-1";
            // Intentionally poor data quality before geofence transitions.
            locationService.reportLocation(scenario.userId, leeds.getLatitude(), leeds.getLongitude(), 250.0);
            locationService.reportLocation(scenario.userId, leeds.getLatitude(), leeds.getLongitude(), 250.0);
            locationService.reportLocation(scenario.userId, sheffield.getLatitude(), sheffield.getLongitude(), 300.0);

            // Still produce a segment with weak confidence inputs.
            geofenceService.recordEvent(scenario.userId, leeds.getId(), GeofenceEvent.EventType.EXITED, base, 180.0, corr);
            geofenceService.recordEvent(
                scenario.userId, sheffield.getId(), GeofenceEvent.EventType.ENTERED, base.plus(24, ChronoUnit.MINUTES), 200.0, corr
            );
            locationService.saveUserLocationOnly(scenario.userId, sheffield.getLatitude(), sheffield.getLongitude());
        }

        TripSegment latest = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(
            scenario.userId, PageRequest.of(0, 1)
        ).stream().findFirst().orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("scenario", scenario.name());
        out.put("userId", scenario.userId);
        out.put("correlationId", scenario.correlationId);
        out.put("segmentId", latest != null ? latest.getId() : null);
        out.put("segmentFareStatus", latest != null && latest.getFareStatus() != null ? latest.getFareStatus().name() : null);
        out.put("segmentConfidenceScore", latest != null ? latest.getConfidenceScore() : null);
        out.put("message", scenario.message);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookingsReady", DEMO_USERS.stream().allMatch(this::hasConfirmedReservation));
        out.put("users", DEMO_USERS);
        out.put("scenarios", List.of("VALID", "OVER_TRAVEL", "LOW_QUALITY"));
        return out;
    }

    private Map<String, Object> scenarioBooking(String scenario, String userId, Reservation booking) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", scenario);
        out.put("userId", userId);
        out.put("reservationId", booking.getId());
        out.put("tripId", booking.getTripSeat() != null && booking.getTripSeat().getTrip() != null ? booking.getTripSeat().getTrip().getId() : null);
        out.put("journeyFrom", booking.getJourneyFromStation());
        out.put("journeyTo", booking.getJourneyToStation());
        out.put("status", booking.getStatus() != null ? booking.getStatus().name() : null);
        return out;
    }

    private Reservation createConfirmedTicket(String userId, String fromStation, String toStation, String journeyFrom, String journeyTo) {
        List<Trip> candidates = tripRepository.findByFromStationAndToStation(fromStation, toStation);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No trip for route " + fromStation + " -> " + toStation);
        }
        Trip trip = null;
        Long seatId = null;
        for (Trip candidate : candidates) {
            Optional<Long> free = findFirstFreeSeatId(candidate.getId());
            if (free.isPresent()) {
                trip = candidate;
                seatId = free.get();
                break;
            }
        }
        if (trip == null || seatId == null) {
            throw new IllegalStateException("No free seat for route " + fromStation + " -> " + toStation);
        }

        Reservation reservation = bookingService.reserve(userId, trip.getId(), List.of(seatId), journeyFrom, journeyTo).get(0);
        bookingService.payment(reservation.getId(), userId, "SIM-DEMO");
        return bookingService.confirm(reservation.getId(), userId);
    }

    private Optional<Long> findFirstFreeSeatId(Long tripId) {
        Instant now = Instant.now();
        List<ReservationStatus> active = List.of(
            ReservationStatus.RESERVED,
            ReservationStatus.PENDING_PAYMENT,
            ReservationStatus.PAYMENT_PROCESSING,
            ReservationStatus.PAID,
            ReservationStatus.CONFIRMED
        );
        for (TripSeat seat : tripSeatRepository.findByTripId(tripId)) {
            if (reservationRepository.findActiveByTripSeatId(seat.getId(), active, now).isEmpty()) {
                return Optional.ofNullable(seat.getSeat()).map(s -> s.getId());
            }
        }
        return Optional.empty();
    }

    private void clearAllDemoArtifacts() {
        clearRuntimeArtifactsForUsers(DEMO_USERS);
        reservationRepository.deleteByUserIdIn(DEMO_USERS);
    }

    private void clearRuntimeArtifactsForUser(String userId) {
        clearRuntimeArtifactsForUsers(List.of(userId));
    }

    private void clearRuntimeArtifactsForUsers(List<String> userIds) {
        List<TripSegment> segments = tripSegmentRepository.findByPassengerIdIn(userIds);
        List<Long> segmentIds = segments.stream().map(TripSegment::getId).toList();

        if (!segmentIds.isEmpty()) {
            segmentTransitionRepository.deleteBySegmentIdIn(segmentIds);
            recomputationRecordRepository.deleteBySegmentIdIn(segmentIds);
            disputeRecordRepository.deleteBySegmentIdIn(segmentIds);
        }
        disputeRecordRepository.deleteByUserIdIn(userIds);
        tripSegmentRepository.deleteByPassengerIdIn(userIds);

        geofenceEventRepository.deleteByUserIdIn(userIds);
        movementEventRepository.deleteByUserIdIn(userIds);
        userLocationRepository.deleteByUserIdIn(userIds);
    }

    private Geofence requireGeofence(String stationName) {
        return geofenceRepository.findAllByStationName(stationName).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing geofence for station: " + stationName));
    }

    private boolean hasConfirmedReservation(String userId) {
        return reservationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .anyMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
    }

    private enum Scenario {
        VALID("VALID", USER_VALID, "demo-valid-corr-1", "Exact route, high quality signals."),
        OVER_TRAVEL("OVER_TRAVEL", USER_OVER_TRAVEL, "demo-overtravel-corr-1", "Ticket shorter than observed segment."),
        LOW_QUALITY("LOW_QUALITY", USER_LOW_QUALITY, "demo-lowquality-corr-1", "Poor quality signals force uncertainty.");

        private final String key;
        private final String userId;
        private final String correlationId;
        private final String message;

        Scenario(String key, String userId, String correlationId, String message) {
            this.key = key;
            this.userId = userId;
            this.correlationId = correlationId;
            this.message = message;
        }

        static Scenario from(String raw) {
            String normalized = raw == null ? "" : raw.trim().toUpperCase().replace('-', '_');
            for (Scenario s : values()) {
                if (s.key.equals(normalized)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("Unknown demo scenario: " + raw + ". Use VALID, OVER_TRAVEL, or LOW_QUALITY.");
        }
    }
}
