package com.train.booking.service;

import com.train.booking.domain.*;
import com.train.booking.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Large-dataset simulated journey tests: 170 scenarios to measure detection accuracy and false positive rate.
 * <p>
 * Distribution: Valid 50, No ticket 40, Over travel 40, Route violation 20, Ticket sharing 20.
 * Reports True Positive Rate (TPR) and False Positive Rate (FPR).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatedJourneyDetectionTest {

    private static final double GPS_ACCURACY = 10.0;
    private static final int JOURNEY_MINUTES = 25;

    @Autowired private GeofenceRepository geofenceRepository;
    @Autowired private GeofenceService geofenceService;
    @Autowired private TripRepository tripRepository;
    @Autowired private TripSeatRepository tripSeatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private TripSegmentRepository tripSegmentRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private SimulationService simulationService;
    @Autowired private JourneyDroolsPipelineService journeyDroolsPipelineService;
    @Autowired private FraudDetectionService fraudDetectionService;

    private Map<String, Long> geofenceIdsByStation;

    @BeforeAll
    void resolveGeofences() {
        geofenceIdsByStation = new HashMap<>();
        for (String station : List.of("Leeds", "Meadowhall Interchange", "Sheffield", "Rotherham Central")) {
            geofenceRepository.findAllByStationName(station).stream()
                .findFirst()
                .ifPresent(g -> geofenceIdsByStation.put(station, g.getId()));
        }
        assertThat(geofenceIdsByStation).hasSize(4);
    }

    /** Find a trip on the given route that has at least one free seat. */
    private Long findTripWithFreeSeat(String fromStation, String toStation) {
        List<Trip> trips = tripRepository.findByFromStationAndToStation(fromStation, toStation);
        Instant now = Instant.now();
        List<ReservationStatus> active = List.of(ReservationStatus.RESERVED, ReservationStatus.PENDING_PAYMENT,
            ReservationStatus.PAYMENT_PROCESSING, ReservationStatus.PAID, ReservationStatus.CONFIRMED);
        for (Trip t : trips) {
            for (TripSeat ts : tripSeatRepository.findByTripId(t.getId())) {
                if (reservationRepository.findActiveByTripSeatId(ts.getId(), active, now).isEmpty()) {
                    return t.getId();
                }
            }
        }
        throw new IllegalStateException("No free seat for " + fromStation + " → " + toStation);
    }

    private void simulateJourneyEvents(String userId, String originStation, String destStation, Instant exitOrigin, Instant enterDest) {
        Long originId = geofenceIdsByStation.get(originStation);
        Long destId = geofenceIdsByStation.get(destStation);
        if (originId == null || destId == null) throw new IllegalArgumentException("Missing geofence: " + originStation + " or " + destStation);
        geofenceService.recordEvent(userId, originId, GeofenceEvent.EventType.ENTERED, exitOrigin.minus(1, ChronoUnit.MINUTES), GPS_ACCURACY);
        geofenceService.recordEvent(userId, originId, GeofenceEvent.EventType.EXITED, exitOrigin, GPS_ACCURACY);
        geofenceService.recordEvent(userId, destId, GeofenceEvent.EventType.ENTERED, enterDest, GPS_ACCURACY);
    }

    @Transactional
    @Test
    @DisplayName("170 simulated journeys: Valid 50, No ticket 40, Over travel 40, Route violation 20, Ticket sharing 20 — report TPR/FPR")
    void simulatedJourneyDetection_largeDataset() {
        int validCount = 50, noTicketCount = 40, overTravelCount = 40, routeViolationCount = 20, ticketSharingCount = 20;
        int totalFraud = noTicketCount + overTravelCount + routeViolationCount + ticketSharingCount;
        int totalValid = validCount;

        List<ScenarioResult> results = new ArrayList<>();

        // --- Valid journeys (50): ticket covers origin → destination → expect PAID, no fraud alert ---
        for (int i = 0; i < validCount; i++) {
            String userId = "sim-valid-" + i;
            Long tripId = findTripWithFreeSeat("Leeds", "Sheffield");
            simulationService.createTicketForUser(userId, tripId);
            Instant exitOrigin = Instant.now().minus(60, ChronoUnit.MINUTES);
            Instant enterDest = exitOrigin.plus(JOURNEY_MINUTES, ChronoUnit.MINUTES);
            simulateJourneyEvents(userId, "Leeds", "Sheffield", exitOrigin, enterDest);
            journeyDroolsPipelineService.runPipelineForUser(userId);

            TripSegment segment = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
            List<String> alerts = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50))
                .stream().map(AuditLog::getAction).filter(a -> isFraudAlertAction(a)).collect(Collectors.toList());

            boolean correct = segment != null && segment.getFareStatus() == FareStatus.PAID && alerts.isEmpty();
            results.add(new ScenarioResult("VALID", userId, correct, segment != null ? segment.getFareStatus().name() : "NONE", alerts));
        }

        // --- No ticket (40): expect unresolved state and/or no-ticket alert ---
        for (int i = 0; i < noTicketCount; i++) {
            String userId = "sim-noticket-" + i;
            Instant exitOrigin = Instant.now().minus(60, ChronoUnit.MINUTES);
            Instant enterDest = exitOrigin.plus(JOURNEY_MINUTES, ChronoUnit.MINUTES);
            simulateJourneyEvents(userId, "Leeds", "Sheffield", exitOrigin, enterDest);
            journeyDroolsPipelineService.runPipelineForUser(userId);

            TripSegment segment = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
            List<String> alerts = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50))
                .stream().map(AuditLog::getAction).filter(a -> isFraudAlertAction(a)).collect(Collectors.toList());

            boolean expectedUnpaid = segment != null && (
                segment.getFareStatus() == FareStatus.PENDING_RESOLUTION
                    || segment.getFareStatus() == FareStatus.PENDING_REVIEW
                    || segment.getFareStatus() == FareStatus.UNPAID_TRAVEL
            );
            boolean expectedAlert = alerts.stream().anyMatch(a -> "NO_TICKET".equals(a));
            boolean correct = expectedUnpaid || expectedAlert;
            results.add(new ScenarioResult("NO_TICKET", userId, correct,
                segment != null ? segment.getFareStatus().name() : "NONE", alerts));
        }

        // --- Over travel (40): ticket Leeds → Meadowhall, journey Leeds → Sheffield ---
        for (int i = 0; i < overTravelCount; i++) {
            String userId = "sim-overtravel-" + i;
            Long tripId = findTripWithFreeSeat("Leeds", "Meadowhall Interchange");
            simulationService.createTicketForUser(userId, tripId);
            Instant exitOrigin = Instant.now().minus(60, ChronoUnit.MINUTES);
            Instant enterDest = exitOrigin.plus(JOURNEY_MINUTES, ChronoUnit.MINUTES);
            simulateJourneyEvents(userId, "Leeds", "Sheffield", exitOrigin, enterDest);
            journeyDroolsPipelineService.runPipelineForUser(userId);

            TripSegment segment = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
            List<String> alerts = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50))
                .stream().map(AuditLog::getAction).filter(a -> isFraudAlertAction(a)).collect(Collectors.toList());

            boolean expectedUnderpaidOrAlert = segment != null && (
                segment.getFareStatus() == FareStatus.UNDERPAID
                    || segment.getFareStatus() == FareStatus.PENDING_RESOLUTION
                    || segment.getFareStatus() == FareStatus.PENDING_REVIEW
            );
            boolean expectedOverTravelAlert = alerts.stream().anyMatch(a -> "OVER_TRAVEL".equals(a));
            boolean correct = (expectedUnderpaidOrAlert || expectedOverTravelAlert);
            results.add(new ScenarioResult("OVER_TRAVEL", userId, correct,
                segment != null ? segment.getFareStatus().name() : "NONE", alerts));
        }

        // --- Route violation (20): ticket Leeds → Sheffield, journey Sheffield → Rotherham ---
        for (int i = 0; i < routeViolationCount; i++) {
            String userId = "sim-routeviol-" + i;
            Long tripId = findTripWithFreeSeat("Leeds", "Sheffield");
            simulationService.createTicketForUser(userId, tripId);
            Instant exitOrigin = Instant.now().minus(60, ChronoUnit.MINUTES);
            Instant enterDest = exitOrigin.plus(JOURNEY_MINUTES, ChronoUnit.MINUTES);
            simulateJourneyEvents(userId, "Sheffield", "Rotherham Central", exitOrigin, enterDest);
            journeyDroolsPipelineService.runPipelineForUser(userId);

            TripSegment segment = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
            List<String> alerts = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50))
                .stream().map(AuditLog::getAction).filter(a -> isFraudAlertAction(a)).collect(Collectors.toList());

            boolean expectedAlert = alerts.stream().anyMatch(a -> "WRONG_ROUTE".equals(a) || "ROUTE_VIOLATION".equals(a));
            boolean expectedUnpaidOrPending = segment != null && (
                segment.getFareStatus() == FareStatus.PENDING_RESOLUTION
                    || segment.getFareStatus() == FareStatus.PENDING_REVIEW
                    || segment.getFareStatus() == FareStatus.UNPAID_TRAVEL
            );
            boolean correct = expectedAlert || expectedUnpaidOrPending;
            results.add(new ScenarioResult("ROUTE_VIOLATION", userId, correct,
                segment != null ? segment.getFareStatus().name() : "NONE", alerts));
        }

        // --- Ticket sharing (20): user A has ticket and travels; user B uses same reservation (segment created manually); run fraud detection → TICKET_SHARING ---
        for (int i = 0; i < ticketSharingCount; i++) {
            String userA = "sim-share-a-" + i;
            String userB = "sim-share-b-" + i;
            Long tripId = findTripWithFreeSeat("Leeds", "Sheffield");
            Reservation res = simulationService.createTicketForUser(userA, tripId);
            Instant exitOrigin = Instant.now().minus(60, ChronoUnit.MINUTES);
            Instant enterDest = exitOrigin.plus(JOURNEY_MINUTES, ChronoUnit.MINUTES);
            simulateJourneyEvents(userA, "Leeds", "Sheffield", exitOrigin, enterDest);
            journeyDroolsPipelineService.runPipelineForUser(userA);

            TripSegment segA = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(userA, PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
            if (segA != null && segA.getReservationId() != null) {
                TripSegment segB = TripSegment.builder()
                    .passengerId(userB)
                    .originStation("Leeds")
                    .destinationStation("Sheffield")
                    .segmentStartTime(exitOrigin.plus(2, ChronoUnit.MINUTES))
                    .segmentEndTime(enterDest.plus(2, ChronoUnit.MINUTES))
                    .fareStatus(FareStatus.PAID)
                    .paidFare(segA.getPaidFare())
                    .idempotencyKey("sim-share-b-" + i + "|Leeds|Sheffield|" + exitOrigin.plus(2, ChronoUnit.MINUTES).getEpochSecond())
                    .reservationId(segA.getReservationId())
                    .build();
                tripSegmentRepository.save(segB);
            }
            fraudDetectionService.runTicketSharingDetection();

            List<AuditLog> sharingLogs = auditLogRepository.findByActionOrderByCreatedAtDesc("TICKET_SHARING", PageRequest.of(0, 20));
            boolean foundSharing = sharingLogs.stream().anyMatch(l -> l.getDetails() != null && l.getDetails().contains(String.valueOf(res.getId())));
            results.add(new ScenarioResult("TICKET_SHARING", userA + "+" + userB, foundSharing, "N/A", foundSharing ? List.of("TICKET_SHARING") : List.of()));
        }

        // --- Aggregate and report ---
        long validCorrect = results.stream().filter(r -> "VALID".equals(r.scenario)).filter(r -> r.correct).count();
        long validTotal = results.stream().filter(r -> "VALID".equals(r.scenario)).count();
        long fraudCorrect = results.stream().filter(r -> !"VALID".equals(r.scenario)).filter(r -> r.correct).count();
        long fraudTotal = results.stream().filter(r -> !"VALID".equals(r.scenario)).count();

        double fpr = validTotal > 0 ? (double) (validTotal - validCorrect) / validTotal : 0.0;
        double tpr = fraudTotal > 0 ? (double) fraudCorrect / fraudTotal : 0.0;

        List<ScenarioResult> failed = results.stream().filter(r -> !r.correct).collect(Collectors.toList());
        if (!failed.isEmpty()) {
            System.out.println("Failed scenarios (first 20): " + failed.stream().limit(20)
                .map(r -> r.scenario + " " + r.userId + " fare=" + r.fareStatus + " alerts=" + r.alertActions).collect(Collectors.joining("; ")));
        }

        System.out.println("Simulated journey detection report:");
        System.out.println("  Valid journeys: " + validCorrect + "/" + validTotal + " correct (FPR = " + String.format("%.2f%%", fpr * 100) + ")");
        System.out.println("  Fraud scenarios: " + fraudCorrect + "/" + fraudTotal + " correct (TPR = " + String.format("%.2f%%", tpr * 100) + ")");
        System.out.println("  Total: " + results.size() + " scenarios");

        assertThat(validTotal).isEqualTo(validCount);
        assertThat(fraudTotal).isEqualTo(totalFraud);
        assertThat(fpr).as("False positive rate should be low").isLessThanOrEqualTo(0.10);
        assertThat(tpr).as("True positive rate should be high").isGreaterThanOrEqualTo(0.85);
    }

    private static boolean isFraudAlertAction(String action) {
        return "NO_TICKET".equals(action) || "OVER_TRAVEL".equals(action) || "WRONG_ROUTE".equals(action)
            || "ROUTE_VIOLATION".equals(action) || "TICKET_SHARING".equals(action) || "EXPIRED_TICKET".equals(action)
            || "MULTI_DEVICE_SUSPICIOUS".equals(action) || "INVESTIGATION_CASE".equals(action);
    }

    private static class ScenarioResult {
        final String scenario;
        final String userId;
        final boolean correct;
        final String fareStatus;
        final List<String> alertActions;

        ScenarioResult(String scenario, String userId, boolean correct, String fareStatus, List<String> alertActions) {
            this.scenario = scenario;
            this.userId = userId;
            this.correct = correct;
            this.fareStatus = fareStatus;
            this.alertActions = alertActions;
        }
    }
}
