package com.train.booking.api;

import com.train.booking.api.dto.*;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.RecomputationRecord;
import com.train.booking.domain.StationEntryAction;
import com.train.booking.domain.TicketAlert;
import com.train.booking.domain.Trip;
import com.train.booking.domain.TripSeat;
import com.train.booking.domain.DisputeRecord;
import com.train.booking.domain.DisputeStatus;
import com.train.booking.domain.SegmentState;
import com.train.booking.domain.SegmentTransition;
import com.train.booking.repository.AuditDecisionRecordRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.SegmentTransitionRepository;
import com.train.booking.repository.TicketAlertRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.domain.Geofence;
import com.train.booking.domain.GeofenceEvent;
import com.train.booking.service.AuditLogService;
import com.train.booking.service.BookingService;
import com.train.booking.domain.UserLocation;
import com.train.booking.domain.UserNotification;
import com.train.booking.repository.UserNotificationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.train.booking.domain.AuditDecisionRecord;
import com.train.booking.domain.TripSegment;
import com.train.booking.movement.projection.PassengerMovementView;
import com.train.booking.movement.projection.PassengerMovementViewRepository;
import com.train.booking.movement.eventlog.MovementEventEntity;
import com.train.booking.movement.eventlog.MovementEventRepository;
import com.train.booking.service.GeofenceService;
import com.train.booking.service.LocationService;
import com.train.booking.service.GeofenceRulesService;
import com.train.booking.service.LoadTestService;
import com.train.booking.service.SimulationService;
import com.train.booking.service.DisputeService;
import com.train.booking.service.DemoScenarioService;
import com.train.booking.service.FraudDetectionService;
import com.train.booking.service.PricingService;
import com.train.booking.service.TripSegmentService;
import com.train.booking.service.TicketPdfService;
import com.train.booking.service.TripQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Profile("!core58")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000", "capacitor://localhost", "http://localhost"})
public class BookingController {

    private final BookingService bookingService;
    private final TripQueryService tripQueryService;
    private final ReservationRepository reservationRepository;
    private final TicketAlertRepository ticketAlertRepository;
    private final TripRepository tripRepository;
    private final AuditLogService auditLogService;
    private final GeofenceService geofenceService;
    private final LocationService locationService;
    private final UserNotificationRepository userNotificationRepository;
    private final GeofenceRulesService geofenceRulesService;
    private final TripSegmentService tripSegmentService;
    private final PassengerMovementViewRepository passengerMovementViewRepository;
    private final MovementEventRepository movementEventRepository;
    private final AuditDecisionRecordRepository auditDecisionRecordRepository;
    private final SegmentTransitionRepository segmentTransitionRepository;
    private final LoadTestService loadTestService;
    private final SimulationService simulationService;
    private final DemoScenarioService demoScenarioService;
    private final DisputeService disputeService;
    private final FraudDetectionService fraudDetectionService;
    private final TicketPdfService ticketPdfService;
    private final PricingService pricingService;
    private final ObjectMapper objectMapper;

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN_HEADER = "X-Admin-Secret";

    @Value("${booking.admin.secret:}")
    private String adminSecret;

    @GetMapping("/trips")
    public List<TripDto> listTrips(
        @RequestHeader(value = USER_HEADER, required = false) String userId,
        @RequestParam(required = false) String fromStation,
        @RequestParam(required = false) String toStation
    ) {
        if (userId != null) auditLogService.log(userId, "VIEW_TRIPS", null);
        return tripQueryService.listTrips(fromStation, toStation).stream()
            .map(t -> toTripDto(t, pricingService.getPrice(t, Instant.now())))
            .collect(Collectors.toList());
    }

    @GetMapping("/trips/{tripId}/seats")
    public List<SeatDto> getSeats(
        @RequestHeader(value = USER_HEADER, required = false) String userId,
        @PathVariable Long tripId
    ) {
        if (userId != null) auditLogService.log(userId, "VIEW_SEATS", "tripId=" + tripId);
        List<Long> bookedIds = tripQueryService.getBookedSeatIdsForTrip(tripId);
        return tripQueryService.getSeatsForTrip(tripId).stream()
            .map(ts -> SeatDto.builder()
                .seatId(ts.getSeat().getId())
                .seatNumber(ts.getSeat().getSeatNumber())
                .available(!bookedIds.contains(ts.getSeat().getId()))
                .build())
            .collect(Collectors.toList());
    }

    @PostMapping("/reserve")
    public ResponseEntity<List<ReservationDto>> reserve(
        @RequestHeader(USER_HEADER) String userId,
        @Valid @RequestBody ReserveRequest request
    ) {
        List<Reservation> reservations = bookingService.reserve(userId, request.getTripId(), request.getSeatIds(),
            request.getJourneyFromStation(), request.getJourneyToStation());
        auditLogService.log(userId, "RESERVE", "tripId=" + request.getTripId() + " seats=" + request.getSeatIds());
        return ResponseEntity.ok(reservations.stream().map(r -> toReservationDto(r)).collect(Collectors.toList()));
    }

    @PostMapping("/reservations/{reservationId}/payment")
    public ResponseEntity<ReservationDto> payment(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId,
        @RequestParam(required = false) String paymentReference
    ) {
        Reservation r = bookingService.payment(reservationId, userId, paymentReference);
        auditLogService.log(userId, "PAYMENT", "reservationId=" + reservationId + " ref=" + r.getPaymentReference());
        return ResponseEntity.ok(toReservationDto(r));
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    public ResponseEntity<ReservationDto> confirm(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId
    ) {
        Reservation r = bookingService.confirm(reservationId, userId);
        auditLogService.log(userId, "CONFIRM", "reservationId=" + reservationId);
        return ResponseEntity.ok(toReservationDto(r));
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ResponseEntity<Void> cancel(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId
    ) {
        bookingService.cancel(reservationId, userId);
        auditLogService.log(userId, "CANCEL", "reservationId=" + reservationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/bookings")
    public List<ReservationDto> getMyBookings(@RequestHeader(USER_HEADER) String userId) {
        auditLogService.log(userId, "VIEW_BOOKINGS", null);
        return reservationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(BookingController::toReservationDto)
            .collect(Collectors.toList());
    }

    @GetMapping("/admin/reservations")
    public List<ReservationDto> getAllReservations(
        @RequestHeader(ADMIN_HEADER) String adminHeader
    ) {
        requireAdmin(adminHeader);
        return reservationRepository.findAllWithDetailsOrderByCreatedAtDesc().stream()
            .map(BookingController::toReservationDto)
            .collect(Collectors.toList());
    }


    /** Tickets view: CONFIRMED/PAID reservations for journey matching and reporting (spec: tickets table). */
    @GetMapping("/admin/tickets")
    public List<TicketDto> listTickets(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(defaultValue = "200") int limit
    ) {
        requireAdmin(adminHeader);
        int max = Math.min(Math.max(1, limit), 500);
        return reservationRepository.findByStatusInOrderByCreatedAtDesc(
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.PAID), PageRequest.of(0, max))
            .stream()
            .map(BookingController::toTicketDto)
            .collect(Collectors.toList());
    }

    /** Run ticket-sharing detection (same reservation, different passengers, overlapping times). Returns count of reservations flagged. */
    @PostMapping("/admin/detect-ticket-sharing")
    public Map<String, Integer> detectTicketSharing(@RequestHeader(ADMIN_HEADER) String adminHeader) {
        requireAdmin(adminHeader);
        int alerts = tripSegmentService.detectTicketSharing();
        return Map.of("alertsRaised", alerts);
    }

    /** Run full fraud detection engine (ticket sharing, refund fraud, multi-device, suspicious account, consistency). Returns counts per type. */
    @PostMapping("/admin/fraud-detection/run")
    public ResponseEntity<FraudDetectionService.FraudDetectionResult> runFraudDetection(
        @RequestHeader(ADMIN_HEADER) String adminHeader
    ) {
        requireAdmin(adminHeader);
        return ResponseEntity.ok(fraudDetectionService.runAll());
    }

    @PostMapping("/admin/alerts")
    public ResponseEntity<TicketAlertDto> alertUserWithoutTicket(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @Valid @RequestBody AlertUserRequest request
    ) {
        requireAdmin(adminHeader);
        Trip trip = tripRepository.findByIdWithTrain(request.getTripId())
            .orElseThrow(() -> new IllegalArgumentException("Trip not found"));
        String message = "You were detected on this train without a valid ticket. Please purchase a ticket for "
            + trip.getFromStation() + " → " + trip.getToStation() + ".";
        TicketAlert alert = TicketAlert.builder()
            .userId(request.getUserId())
            .tripId(request.getTripId())
            .message(message)
            .build();
        alert = ticketAlertRepository.save(alert);
        auditLogService.log("admin", "ALERT_USER", "userId=" + request.getUserId() + " tripId=" + request.getTripId());
        TicketAlertDto dto = toTicketAlertDto(alert);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/admin/audit-logs")
    public List<AuditLogDto> getAuditLogs(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(required = false) String userId,
        @RequestParam(defaultValue = "200") int limit
    ) {
        requireAdmin(adminHeader);
        int maxLimit = Math.min(Math.max(1, limit), 500);
        List<com.train.booking.domain.AuditLog> logs = userId != null && !userId.isBlank()
            ? auditLogService.findByUserId(userId, maxLimit)
            : auditLogService.findAll(maxLimit);
        return logs.stream().map(BookingController::toAuditLogDto).collect(Collectors.toList());
    }

    @GetMapping("/admin/geofences")
    public List<GeofenceDto> listGeofences(@RequestHeader(ADMIN_HEADER) String adminHeader) {
        requireAdmin(adminHeader);
        return geofenceService.listGeofences().stream().map(BookingController::toGeofenceDto).collect(Collectors.toList());
    }

    @PostMapping("/admin/geofences")
    public ResponseEntity<GeofenceDto> createGeofence(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @Valid @RequestBody GeofenceDto request
    ) {
        requireAdmin(adminHeader);
        Geofence g = geofenceService.createGeofence(
            request.getName(), request.getStationName(),
            request.getLatitude(), request.getLongitude(),
            request.getRadiusMeters() != null ? request.getRadiusMeters() : 100);
        return ResponseEntity.ok(toGeofenceDto(g));
    }

    @PostMapping("/admin/geofence-events")
    public ResponseEntity<GeofenceEventDto> recordGeofenceEvent(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @Valid @RequestBody RecordGeofenceEventRequest request
    ) {
        requireAdmin(adminHeader);
        GeofenceEvent.EventType type = GeofenceEvent.EventType.valueOf(request.getEventType().toUpperCase());
        String correlationId = request.getCorrelationId() != null && !request.getCorrelationId().isBlank()
            ? request.getCorrelationId()
            : UUID.randomUUID().toString();
        GeofenceEvent event = geofenceService.recordEvent(
            request.getUserId(), request.getGeofenceId(), type, null, null, correlationId);
        return ResponseEntity.ok(toGeofenceEventDto(event));
    }

    @GetMapping("/admin/geofence-events")
    public List<GeofenceEventDto> listGeofenceEvents(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireAdmin(adminHeader);
        return geofenceService.listRecentEvents(limit).stream()
            .map(BookingController::toGeofenceEventDto)
            .collect(Collectors.toList());
    }

    @GetMapping("/admin/fare-evasion")
    public List<AuditLogDto> listFareEvasion(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireAdmin(adminHeader);
        return geofenceService.listFareEvasionAudit(limit).stream()
            .map(BookingController::toAuditLogDto)
            .collect(Collectors.toList());
    }

    @GetMapping("/my/trip-segments")
    public List<TripSegmentDto> getMyTripSegments(
        @RequestHeader(USER_HEADER) String userId,
        @RequestParam(defaultValue = "50") int limit
    ) {
        int max = Math.min(Math.max(1, limit), 100);
        return tripSegmentService.listByPassenger(userId, max).stream()
            .map(this::toTripSegmentDto)
            .collect(Collectors.toList());
    }

    /** Current station derived from user's last reported location (geofence containing or nearest). */
    @GetMapping("/my/current-station")
    public ResponseEntity<Map<String, String>> getMyCurrentStation(@RequestHeader(USER_HEADER) String userId) {
        return locationService.getCurrentStation(userId)
            .map(g -> ResponseEntity.<Map<String, String>>ok(Map.of(
                "stationName", g.getStationName(),
                "displayName", g.getName())))
            .orElse(ResponseEntity.notFound().build());
    }

    /** Departures from a station (future only), ordered by time. Used for dashboard by platform. */
    @GetMapping("/stations/{stationName}/departures")
    public List<TripDto> getStationDepartures(@PathVariable String stationName) {
        return tripQueryService.listDeparturesFromStation(stationName).stream()
            .map(BookingController::toTripDto)
            .collect(Collectors.toList());
    }

    /** Passenger uploads ticket proof to dispute a segment marked as no ticket (UNPAID_TRAVEL/UNDERPAID). */
    @PostMapping("/my/trip-segments/{segmentId}/upload-ticket")
    public ResponseEntity<TripSegmentDto> uploadTicketForSegment(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long segmentId,
        @Valid @RequestBody UploadTicketRequest request
    ) {
        TripSegment updated = disputeService.submitDisputeAndRecompute(
                segmentId,
                userId,
                request.getReservationId(),
                request.getReason(),
                request.getEvidenceReference())
            .orElseThrow();
        return ResponseEntity.ok(toTripSegmentDto(updated));
    }

    /** Passenger/admin: list dispute records for a segment (append-only lineage). */
    @GetMapping("/my/trip-segments/{segmentId}/disputes")
    public List<DisputeRecordDto> listSegmentDisputes(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long segmentId
    ) {
        return disputeService.listBySegment(segmentId, userId).stream()
            .map(BookingController::toDisputeRecordDto)
            .collect(Collectors.toList());
    }

    /** Admin: review all disputes, optional status filter. */
    @GetMapping("/admin/disputes")
    public List<DisputeRecordDto> listDisputes(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(required = false) String status
    ) {
        requireAdmin(adminHeader);
        DisputeStatus s = null;
        if (status != null && !status.isBlank()) {
            s = DisputeStatus.valueOf(status.toUpperCase());
        }
        return disputeService.listByStatus(s).stream()
            .map(BookingController::toDisputeRecordDto)
            .collect(Collectors.toList());
    }

    @PatchMapping("/admin/disputes/{disputeId}/under-review")
    public DisputeRecordDto markDisputeUnderReview(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @PathVariable UUID disputeId
    ) {
        requireAdmin(adminHeader);
        return toDisputeRecordDto(disputeService.markUnderReview(disputeId, "ADMIN"));
    }

    @PatchMapping("/admin/disputes/{disputeId}/accept")
    public DisputeRecordDto acceptDispute(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @PathVariable UUID disputeId
    ) {
        requireAdmin(adminHeader);
        return toDisputeRecordDto(disputeService.acceptDispute(disputeId, "ADMIN"));
    }

    @PatchMapping("/admin/disputes/{disputeId}/reject")
    public DisputeRecordDto rejectDispute(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @PathVariable UUID disputeId
    ) {
        requireAdmin(adminHeader);
        return toDisputeRecordDto(disputeService.rejectDispute(disputeId, "ADMIN"));
    }

    /** Admin/passenger: recomputation lineage for one segment. */
    @GetMapping("/my/trip-segments/{segmentId}/recomputations")
    public List<RecomputationRecordDto> listSegmentRecomputations(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long segmentId
    ) {
        return disputeService.listRecomputationsForSegment(segmentId, userId).stream()
            .map(this::toRecomputationRecordDto)
            .collect(Collectors.toList());
    }

    @GetMapping("/admin/trip-segments")
    public List<TripSegmentDto> listTripSegments(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(required = false) String passengerId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireAdmin(adminHeader);
        List<TripSegment> segments = passengerId != null && !passengerId.isBlank()
            ? tripSegmentService.listByPassenger(passengerId, limit)
            : tripSegmentService.listAll(limit);
        return segments.stream().map(this::toTripSegmentDto).collect(Collectors.toList());
    }

    /** Admin: fare evasion cases (PENDING_RESOLUTION + UNPAID_TRAVEL) for monitoring. */
    @GetMapping("/admin/fare-evasion-cases")
    public List<TripSegmentDto> listFareEvasionCases(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireAdmin(adminHeader);
        return tripSegmentService.listFareEvasionCases(limit).stream()
            .map(this::toTripSegmentDto)
            .collect(Collectors.toList());
    }

    /** Admin: set review lifecycle state for fareStatus=PENDING_REVIEW segments. */
    @PatchMapping("/admin/trip-segments/{segmentId}/review-state")
    public TripSegmentDto updatePendingReviewState(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @PathVariable Long segmentId,
        @Valid @RequestBody AdminPendingReviewStateRequest request
    ) {
        requireAdmin(adminHeader);
        SegmentState nextState = SegmentState.valueOf(request.getState().toUpperCase());
        TripSegment updated = tripSegmentService.updatePendingReviewState(
            segmentId, nextState, request.getNote(), "ADMIN"
        ).orElseThrow();
        return toTripSegmentDto(updated);
    }

    /**
     * Ingestion endpoint: accepts only minimal movement signals for the current report.
     * User identity is pseudonymous, and raw coordinates are converted into transient events
     * rather than building a continuous movement timeline.
     */
    @PostMapping("/location")
    public ResponseEntity<UserLocationDto> reportLocation(
        @RequestHeader(USER_HEADER) String userId,
        @Valid @RequestBody ReportLocationRequest request
    ) {
        UserLocation loc = locationService.reportLocation(userId, request.getLatitude(), request.getLongitude(), request.getAccuracyMeters());
        return ResponseEntity.ok(toUserLocationDto(loc));
    }

   
    @GetMapping("/admin/user-locations")
    public List<PassengerMovementAdminDto> getUserLocations(@RequestHeader(ADMIN_HEADER) String adminHeader) {
        requireAdmin(adminHeader);
        return passengerMovementViewRepository.findAll().stream()
            .map(BookingController::toPassengerMovementAdminDto)
            .collect(Collectors.toList());
    }


    @GetMapping("/admin/passenger-movement")
    public List<PassengerMovementView> listPassengerMovement(@RequestHeader(ADMIN_HEADER) String adminHeader) {
        requireAdmin(adminHeader);
        return passengerMovementViewRepository.findAll();
    }

 
    @GetMapping("/admin/movement-events")
    public List<MovementEventDto> listMovementEvents(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(required = false) String userId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireAdmin(adminHeader);
        int max = Math.min(Math.max(1, limit), 500);
        List<MovementEventEntity> events = userId != null && !userId.isBlank()
            ? movementEventRepository.findByUserIdOrderByRecordedAtDesc(userId, PageRequest.of(0, max)).getContent()
            : movementEventRepository.findAllByOrderByRecordedAtDesc(PageRequest.of(0, max)).getContent();
        return events.stream().map(this::toMovementEventDto).collect(Collectors.toList());
    }

    /** Admin: evidence view for a single trace (correlationId → timeline + narrative). */
    @GetMapping("/admin/evidence")
    public ResponseEntity<AdminEvidenceDto> getEvidence(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam String correlationId,
        @RequestParam(defaultValue = "200") int limit
    ) {
        requireAdmin(adminHeader);
        if (correlationId == null || correlationId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int max = Math.min(Math.max(1, limit), 500);
        List<MovementEventEntity> rows = movementEventRepository
            .findByCorrelationIdOrderByRecordedAtAsc(correlationId, PageRequest.of(0, max))
            .getContent();
        List<MovementEventDto> timeline = rows.stream().map(this::toMovementEventDto).collect(Collectors.toList());
        String userId = rows.isEmpty() ? null : rows.get(0).getUserId();
        List<String> narrative = EvidenceNarrativeBuilder.build(timeline);
        List<SegmentTransitionDto> transitions = resolveEvidenceTransitions(correlationId, timeline);

        return ResponseEntity.ok(AdminEvidenceDto.builder()
            .correlationId(correlationId)
            .userId(userId)
            .narrative(narrative)
            .timeline(timeline)
            .segmentTransitions(transitions)
            .build());
    }

    /** Admin: structured audit trail for central fare/fraud decisions (admin-supervision). */
    @GetMapping("/admin/audit-decisions")
    public List<AuditDecisionRecordDto> listAuditDecisions(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(required = false) String userId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireAdmin(adminHeader);
        int max = Math.min(Math.max(1, limit), 500);
        List<AuditDecisionRecord> rows = userId != null && !userId.isBlank()
            ? auditDecisionRecordRepository.findByUserIdOrderByRecordedAtDesc(userId, PageRequest.of(0, max))
            : auditDecisionRecordRepository.findAll(PageRequest.of(0, max, Sort.by(Sort.Direction.DESC, "recordedAt")))
                .getContent();
        return rows.stream().map(BookingController::toAuditDecisionRecordDto).collect(Collectors.toList());
    }

    /** Simulate one user (e.g. 4) travelling Sheffield → Doncaster with no ticket; creates trip segment with UNPAID_TRAVEL. */
    @PostMapping("/admin/geofence-events/simulate-no-ticket")
    public ResponseEntity<Map<String, String>> simulateUserNoTicket(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(defaultValue = "4") String userId
    ) {
        requireAdmin(adminHeader);
        Geofence sheffield = geofenceService.findGeofenceByName("Sheffield Station")
            .orElseThrow(() -> new IllegalArgumentException("Sheffield Station geofence not found"));
        Geofence doncaster = geofenceService.findGeofenceByName("Doncaster Station")
            .orElseThrow(() -> new IllegalArgumentException("Doncaster Station geofence not found"));
        geofenceService.recordEvent(userId, sheffield.getId(), GeofenceEvent.EventType.ENTERED);
        geofenceService.recordEvent(userId, sheffield.getId(), GeofenceEvent.EventType.EXITED);
        geofenceService.recordEvent(userId, doncaster.getId(), GeofenceEvent.EventType.ENTERED);
        locationService.saveUserLocationOnly(userId, doncaster.getLatitude(), doncaster.getLongitude());
        return ResponseEntity.ok(Map.of("status", "ok", "message", "User " + userId + " Sheffield → Doncaster (no ticket); trip segment created, UNPAID_TRAVEL + penalty."));
    }

    /**
     * Simulate Doncaster → Sheffield journey with real-time timestamps (no ticket).
     * User enters Doncaster at enterOriginAt, exits Doncaster ~1 min later, enters Sheffield at enterDestinationAt.
     * Pipeline creates trip segment and PENDING_RESOLUTION (1-hour window) or penalty.
     */
    @PostMapping("/admin/geofence-events/simulate-journey-doncaster-sheffield")
    public ResponseEntity<Map<String, Object>> simulateJourneyDoncasterSheffield(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @Valid @RequestBody SimulateJourneyRequest request
    ) {
        requireAdmin(adminHeader);
        Geofence origin = geofenceService.findGeofenceByName("Doncaster Station")
            .orElseThrow(() -> new IllegalArgumentException("Doncaster Station geofence not found"));
        Geofence dest = geofenceService.findGeofenceByName("Sheffield Station")
            .orElseThrow(() -> new IllegalArgumentException("Sheffield Station geofence not found"));
        return simulateJourney(adminHeader, request.getUserId(), origin.getId(), dest.getId(),
            request.getEnterOriginAt(), request.getEnterDestinationAt(), origin.getStationName(), dest.getStationName(), request.getAccuracyMeters());
    }

    /**
     * Simulate a journey between any two station geofences (real-time, no ticket).
     * User enters origin at enterOriginAt, exits origin ~1 min later, enters destination at enterDestinationAt.
     * Trip segment and PENDING_RESOLUTION created for fare evasion flow.
     */
    @PostMapping("/admin/geofence-events/simulate-journey")
    public ResponseEntity<Map<String, Object>> simulateJourneyAny(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @Valid @RequestBody SimulateJourneyRequest request
    ) {
        requireAdmin(adminHeader);
        if (request.getOriginGeofenceId() == null || request.getDestinationGeofenceId() == null) {
            throw new IllegalArgumentException("originGeofenceId and destinationGeofenceId are required");
        }
        Geofence origin = geofenceService.getGeofenceById(request.getOriginGeofenceId());
        Geofence dest = geofenceService.getGeofenceById(request.getDestinationGeofenceId());
        return simulateJourney(adminHeader, request.getUserId(), origin.getId(), dest.getId(),
            request.getEnterOriginAt(), request.getEnterDestinationAt(), origin.getStationName(), dest.getStationName(), request.getAccuracyMeters());
    }

    private ResponseEntity<Map<String, Object>> simulateJourney(String adminHeader, String userId, Long originId, Long destId,
        String enterOriginAt, String enterDestinationAt, String originName, String destName, Double accuracyMeters) {
        requireAdmin(adminHeader);
        Instant enterOrigin = java.time.Instant.parse(enterOriginAt);
        Instant enterDest = java.time.Instant.parse(enterDestinationAt);
        Instant exitOrigin = enterOrigin.plusSeconds(60);

        Geofence origin = geofenceService.getGeofenceById(originId);
        Geofence dest = geofenceService.getGeofenceById(destId);

        geofenceService.recordEvent(userId, origin.getId(), GeofenceEvent.EventType.ENTERED, enterOrigin, accuracyMeters);
        geofenceService.recordEvent(userId, origin.getId(), GeofenceEvent.EventType.EXITED, exitOrigin, accuracyMeters);
        geofenceService.recordEvent(userId, dest.getId(), GeofenceEvent.EventType.ENTERED, enterDest, accuracyMeters);

        locationService.saveUserLocationOnly(userId, dest.getLatitude(), dest.getLongitude());

        long durationMinutes = java.time.Duration.between(enterOrigin, enterDest).toMinutes();
        String message = "Simulated " + originName + " → " + destName + ": user " + userId
            + " entered at " + enterOriginAt + ", entered " + destName + " at " + enterDestinationAt
            + " (duration " + durationMinutes + " min). Trip segment created; check fare evasion cases.";
        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "message", message,
            "userId", userId,
            "origin", originName,
            "destination", destName,
            "enterOriginAt", enterOriginAt,
            "enterDestinationAt", enterDestinationAt,
            "durationMinutes", durationMinutes
        ));
    }

    /** Run full simulation: one user with ticket (PAID), one without (PENDING_RESOLUTION). Returns segments with confidence and train match. */
    @PostMapping("/admin/simulation/run")
    public ResponseEntity<SimulationService.SimulationResult> runSimulation(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(required = false) Long tripId
    ) {
        requireAdmin(adminHeader);
        SimulationService.SimulationResult result = simulationService.runFullSimulation(tripId);
        return ResponseEntity.ok(result);
    }

    /** Demo prep: recreate deterministic fixtures (3 bookings) for recording. */
    @PostMapping("/admin/demo/reset")
    public ResponseEntity<Map<String, Object>> resetDemoFixtures(@RequestHeader(ADMIN_HEADER) String adminHeader) {
        requireAdmin(adminHeader);
        return ResponseEntity.ok(demoScenarioService.resetFixtures());
    }

    /** Demo replay: deterministic event sequence per scenario (VALID, OVER_TRAVEL, LOW_QUALITY). */
    @PostMapping("/admin/demo/replay/{scenario}")
    public ResponseEntity<Map<String, Object>> replayDemoScenario(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @PathVariable String scenario
    ) {
        requireAdmin(adminHeader);
        return ResponseEntity.ok(demoScenarioService.replayScenario(scenario));
    }

    /** Demo readiness: confirms seeded bookings and scenario definitions exist. */
    @GetMapping("/admin/demo/health")
    public ResponseEntity<Map<String, Object>> demoHealth(@RequestHeader(ADMIN_HEADER) String adminHeader) {
        requireAdmin(adminHeader);
        return ResponseEntity.ok(demoScenarioService.health());
    }

    /** Load test: simulate hundreds of users entering a geofence simultaneously. Uses atomic transactions and idempotency. */
    @PostMapping("/admin/load-test/geofence-entries")
    public ResponseEntity<LoadTestService.LoadTestResult> loadTestGeofenceEntries(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam Long geofenceId,
        @RequestParam(defaultValue = "300") int concurrentUsers
    ) {
        requireAdmin(adminHeader);
        LoadTestService.LoadTestResult result = loadTestService.runConcurrentGeofenceEntries(geofenceId, concurrentUsers);
        return ResponseEntity.ok(result);
    }

    /** Load test: simulate multiple ticket QR validations at the same time. Pass list of { actionId, userId, reservationId }. */
    @PostMapping("/admin/load-test/validate-qr")
    public ResponseEntity<LoadTestService.LoadTestResult> loadTestValidateQr(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestBody List<LoadTestService.QrValidationRequest> validations
    ) {
        requireAdmin(adminHeader);
        LoadTestService.LoadTestResult result = loadTestService.runConcurrentQrValidations(validations != null ? validations : List.of());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/admin/notifications")
    public ResponseEntity<UserNotificationDto> sendNotification(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestBody Map<String, String> request
    ) {
        requireAdmin(adminHeader);
        String userId = request != null ? request.get("userId") : null;
        String message = request != null ? request.get("message") : null;
        if (userId == null || userId.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("userId and message are required");
        }
        String msg = message.length() > 1000 ? message.substring(0, 1000) : message;
        UserNotification n = UserNotification.builder()
            .userId(userId)
            .message(msg)
            .build();
        n = userNotificationRepository.save(n);
        return ResponseEntity.ok(toUserNotificationDto(n));
    }

    @GetMapping("/notifications")
    public List<UserNotificationDto> getMyNotifications(@RequestHeader(USER_HEADER) String userId) {
        return userNotificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(BookingController::toUserNotificationDto)
            .collect(Collectors.toList());
    }

    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<UserNotificationDto> markNotificationRead(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long id
    ) {
        UserNotification n = userNotificationRepository.findById(id)
            .filter(not -> userId.equals(not.getUserId()))
            .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        n.setReadAt(Instant.now());
        n = userNotificationRepository.save(n);
        return ResponseEntity.ok(toUserNotificationDto(n));
    }


    @GetMapping("/station-entry-actions/pending")
    public List<StationEntryActionDto> getPendingStationEntryActions(@RequestHeader(USER_HEADER) String userId) {
        return geofenceRulesService.findPendingByUser(userId).stream()
            .map(BookingController::toStationEntryActionDto)
            .collect(Collectors.toList());
    }

    @PostMapping("/station-entry-actions/{actionId}/respond")
    public ResponseEntity<StationEntryActionDto> respondToStationEntry(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long actionId,
        @Valid @RequestBody StationEntryRespondRequest request
    ) {
        StationEntryAction.ResponseType choice = StationEntryAction.ResponseType.valueOf(request.getChoice());
        return geofenceRulesService.respondToAction(actionId, userId, choice)
            .map(BookingController::toStationEntryActionDto)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/station-entry-actions/{actionId}/validate-qr")
    public ResponseEntity<StationEntryActionDto> validateTicketQr(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long actionId,
        @Valid @RequestBody ValidateQrRequest request
    ) {
        return geofenceRulesService.validateQrAndCompleteAction(actionId, userId, request.getReservationId())
            .map(BookingController::toStationEntryActionDto)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.badRequest().build());
    }

    @GetMapping("/alerts")
    public List<TicketAlertDto> getMyAlerts(@RequestHeader(USER_HEADER) String userId) {
        return ticketAlertRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(this::toTicketAlertDto)
            .collect(Collectors.toList());
    }

    @PatchMapping("/alerts/{alertId}/read")
    public ResponseEntity<TicketAlertDto> markAlertRead(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long alertId
    ) {
        TicketAlert alert = ticketAlertRepository.findById(alertId)
            .filter(a -> userId.equals(a.getUserId()))
            .orElseThrow(() -> new IllegalArgumentException("Alert not found"));
        alert.setReadAt(Instant.now());
        alert = ticketAlertRepository.save(alert);
        auditLogService.log(userId, "ALERT_DISMISSED", "alertId=" + alertId);
        return ResponseEntity.ok(toTicketAlertDto(alert));
    }

    @GetMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationDto> getReservation(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId
    ) {
        return reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .map(BookingController::toReservationDto)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** Validate a ticket by reservation ID (e.g. from scanned QR). Returns valid + summary if owned and CONFIRMED/PAID. */
    @GetMapping("/reservations/{reservationId}/validate")
    public ResponseEntity<Map<String, Object>> validateTicket(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId
    ) {
        return reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .filter(r -> r.getStatus() == com.train.booking.domain.ReservationStatus.CONFIRMED
                || r.getStatus() == com.train.booking.domain.ReservationStatus.PAID)
            .map(r -> {
                com.train.booking.domain.Trip t = r.getTripSeat().getTrip();
                String from = (r.getJourneyFromStation() != null && !r.getJourneyFromStation().isBlank()) ? r.getJourneyFromStation() : (t != null ? t.getFromStation() : "");
                String to = (r.getJourneyToStation() != null && !r.getJourneyToStation().isBlank()) ? r.getJourneyToStation() : (t != null ? t.getToStation() : "");
                return ResponseEntity.<Map<String, Object>>ok(Map.of(
                    "valid", true,
                    "reservationId", r.getId(),
                    "fromStation", from,
                    "toStation", to,
                    "departureTime", t != null && t.getDepartureTime() != null ? t.getDepartureTime().toString() : "",
                    "status", r.getStatus().name()
                ));
            })
            .orElse(ResponseEntity.ok(Map.of("valid", false, "message", "Ticket not found or not valid for travel")));
    }

    /** Returns payload for ticket QR (reservationId + journey for display). Used to display QR for "bought from another" scan flow. */
    @GetMapping("/reservations/{reservationId}/ticket-qr")
    public ResponseEntity<Map<String, Object>> getTicketQrPayload(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId
    ) {
        return reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .filter(r -> r.getStatus() == com.train.booking.domain.ReservationStatus.CONFIRMED)
            .map(r -> {
                com.train.booking.domain.Trip t = r.getTripSeat().getTrip();
                String from = (r.getJourneyFromStation() != null && !r.getJourneyFromStation().isBlank()) ? r.getJourneyFromStation() : (t != null ? t.getFromStation() : "");
                String to = (r.getJourneyToStation() != null && !r.getJourneyToStation().isBlank()) ? r.getJourneyToStation() : (t != null ? t.getToStation() : "");
                return ResponseEntity.<Map<String, Object>>ok(Map.of(
                    "reservationId", r.getId(),
                    "fromStation", from,
                    "toStation", to
                ));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /** Download ticket as PDF. Allowed for CONFIRMED or PAID reservations owned by the user. */
    @GetMapping(value = "/reservations/{reservationId}/ticket-pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadTicketPdf(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId
    ) {
        return reservationRepository.findByIdAndUserIdWithFullDetails(reservationId, userId)
            .filter(r -> r.getStatus() == com.train.booking.domain.ReservationStatus.CONFIRMED
                || r.getStatus() == com.train.booking.domain.ReservationStatus.PAID)
            .map(r -> {
                byte[] pdf = ticketPdfService.generateTicketPdf(r);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentDispositionFormData("attachment", "ticket-" + reservationId + ".pdf");
                return ResponseEntity.ok().headers(headers).body(pdf);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    private static TripDto toTripDto(Trip t) {
        return toTripDto(t, null);
    }

    private static TripDto toTripDto(Trip t, PricingService.PricingResult pricing) {
        return TripDto.builder()
            .id(t.getId())
            .fromStation(t.getFromStation())
            .toStation(t.getToStation())
            .departureTime(t.getDepartureTime())
            .platform(t.getPlatform())
            .pricePerSeat(pricing != null ? pricing.getPrice() : t.getPricePerSeat())
            .fareTier(pricing != null ? pricing.getTierName() : null)
            .trainName(t.getTrain().getName())
            .trainCode(t.getTrain().getCode())
            .build();
    }

    private void requireAdmin(String adminHeader) {
        if (adminSecret == null || adminSecret.isBlank() || !adminSecret.equals(adminHeader)) {
            throw new IllegalArgumentException("Invalid admin secret");
        }
    }

    private TicketAlertDto toTicketAlertDto(TicketAlert a) {
        Trip t = tripRepository.findByIdWithTrain(a.getTripId()).orElse(null);
        return TicketAlertDto.builder()
            .id(a.getId())
            .userId(a.getUserId())
            .tripId(a.getTripId())
            .message(a.getMessage())
            .createdAt(a.getCreatedAt())
            .readAt(a.getReadAt())
            .fromStation(t != null ? t.getFromStation() : null)
            .toStation(t != null ? t.getToStation() : null)
            .trainName(t != null ? t.getTrain().getName() : null)
            .departureTime(t != null ? t.getDepartureTime() : null)
            .build();
    }

    private static TicketDto toTicketDto(Reservation r) {
        Trip t = r.getTripSeat().getTrip();
        String from = (r.getJourneyFromStation() != null && !r.getJourneyFromStation().isBlank()) ? r.getJourneyFromStation() : (t != null ? t.getFromStation() : null);
        String to = (r.getJourneyToStation() != null && !r.getJourneyToStation().isBlank()) ? r.getJourneyToStation() : (t != null ? t.getToStation() : null);
        return TicketDto.builder()
            .id(r.getId())
            .userId(r.getUserId())
            .fromStation(from != null ? from : (t != null ? t.getFromStation() : null))
            .toStation(to != null ? to : (t != null ? t.getToStation() : null))
            .departureTime(t != null ? t.getDepartureTime() : null)
            .trainName(t != null && t.getTrain() != null ? t.getTrain().getName() : null)
            .trainCode(t != null && t.getTrain() != null ? t.getTrain().getCode() : null)
            .seatNumber(r.getTripSeat() != null && r.getTripSeat().getSeat() != null ? r.getTripSeat().getSeat().getSeatNumber() : null)
            .amount(r.getAmount())
            .status(r.getStatus().name())
            .build();
    }

    private static ReservationDto toReservationDto(Reservation r) {
        Trip t = r.getTripSeat().getTrip();
        return ReservationDto.builder()
            .id(r.getId())
            .status(r.getStatus().name())
            .amount(r.getAmount())
            .expiresAt(r.getExpiresAt())
            .createdAt(r.getCreatedAt())
            .updatedAt(r.getUpdatedAt())
            .paymentReference(r.getPaymentReference())
            .paymentGateway(r.getPaymentGateway())
            .paymentTransactionId(r.getPaymentTransactionId())
            .currency(r.getCurrency())
            .seats(List.of(SeatDto.builder()
                .seatId(r.getTripSeat().getSeat().getId())
                .seatNumber(r.getTripSeat().getSeat().getSeatNumber())
                .available(false)
                .build()))
            .trip(toTripDto(t))
            .userId(r.getUserId())
            .journeyFromStation(r.getJourneyFromStation())
            .journeyToStation(r.getJourneyToStation())
            .build();
    }

    private static AuditLogDto toAuditLogDto(com.train.booking.domain.AuditLog a) {
        return AuditLogDto.builder()
            .id(a.getId())
            .userId(a.getUserId())
            .action(a.getAction())
            .details(a.getDetails())
            .createdAt(a.getCreatedAt())
            .build();
    }

    private static GeofenceDto toGeofenceDto(Geofence g) {
        return GeofenceDto.builder()
            .id(g.getId())
            .name(g.getName())
            .stationName(g.getStationName())
            .platform(g.getPlatform())
            .latitude(g.getLatitude())
            .longitude(g.getLongitude())
            .radiusMeters(g.getRadiusMeters())
            .build();
    }

    private static GeofenceEventDto toGeofenceEventDto(GeofenceEvent e) {
        return GeofenceEventDto.builder()
            .id(e.getId())
            .userId(e.getUserId())
            .geofence(toGeofenceDto(e.getGeofence()))
            .eventType(e.getEventType().name())
            .createdAt(e.getCreatedAt())
            .build();
    }

    private static UserLocationDto toUserLocationDto(UserLocation loc) {
        return UserLocationDto.builder()
            .userId(loc.getUserId())
            .latitude(loc.getLatitude())
            .longitude(loc.getLongitude())
            .updatedAt(loc.getUpdatedAt())
            .build();
    }

    private static UserNotificationDto toUserNotificationDto(UserNotification n) {
        return UserNotificationDto.builder()
            .id(n.getId())
            .userId(n.getUserId())
            .message(n.getMessage())
            .createdAt(n.getCreatedAt())
            .readAt(n.getReadAt())
            .build();
    }


    private static StationEntryActionDto toStationEntryActionDto(StationEntryAction a) {
        return StationEntryActionDto.builder()
            .id(a.getId())
            .geofenceId(a.getGeofenceId())
            .stationName(a.getStationName())
            .status(a.getStatus().name())
            .responseType(a.getResponseType() != null ? a.getResponseType().name() : null)
            .createdAt(a.getCreatedAt())
            .respondedAt(a.getRespondedAt())
            .qrValidatedReservationId(a.getQrValidatedReservationId())
            .build();
    }

    private static PassengerMovementAdminDto toPassengerMovementAdminDto(PassengerMovementView v) {
        return PassengerMovementAdminDto.builder()
            .userId(v.getUserId())
            .currentStation(v.getCurrentStation())
            .currentPlatform(v.getCurrentPlatform())
            .lastGeofenceEventType(v.getLastGeofenceEventType())
            .journeyStatus(v.getJourneyStatus())
            .candidateOriginStation(v.getCandidateOriginStation())
            .lastEventAt(v.getLastEventAt())
            .lastConfirmedSegmentId(v.getLastConfirmedSegmentId())
            .updatedAt(v.getUpdatedAt())
            .build();
    }

    private static AuditDecisionRecordDto toAuditDecisionRecordDto(AuditDecisionRecord r) {
        return AuditDecisionRecordDto.builder()
            .id(r.getId())
            .userId(r.getUserId())
            .segmentId(r.getSegmentId())
            .decisionType(r.getDecisionType())
            .correlationId(r.getCorrelationId())
            .sourceLayer(r.getSourceLayer())
            .payloadJson(r.getPayloadJson())
            .recordedAt(r.getRecordedAt())
            .build();
    }

    private static DisputeRecordDto toDisputeRecordDto(DisputeRecord d) {
        return DisputeRecordDto.builder()
            .id(d.getId())
            .segmentId(d.getSegmentId())
            .userId(d.getUserId())
            .reason(d.getReason())
            .evidenceReference(d.getEvidenceReference())
            .status(d.getStatus() != null ? d.getStatus().name() : null)
            .submittedAt(d.getSubmittedAt())
            .decidedAt(d.getDecidedAt())
            .decidedBy(d.getDecidedBy())
            .build();
    }

    private RecomputationRecordDto toRecomputationRecordDto(RecomputationRecord r) {
        Map<String, Object> explanation = null;
        if (r.getExplanationJson() != null && !r.getExplanationJson().isBlank()) {
            try {
                explanation = objectMapper.readValue(r.getExplanationJson(), new TypeReference<>() { });
            } catch (Exception ignored) { }
        }
        return RecomputationRecordDto.builder()
            .id(r.getId())
            .segmentId(r.getSegmentId())
            .disputeId(r.getDisputeId())
            .previousDecision(r.getPreviousDecision())
            .recomputedDecision(r.getRecomputedDecision())
            .recomputedConfidence(r.getRecomputedConfidence())
            .recomputedAt(r.getRecomputedAt())
            .explanation(explanation)
            .build();
    }

    private TripSegmentDto toTripSegmentDto(TripSegment s) {
        List<SegmentTransitionDto> transitions = segmentTransitionRepository.findBySegmentIdOrderByOccurredAtAsc(s.getId()).stream()
            .map(BookingController::toSegmentTransitionDto)
            .collect(Collectors.toList());
        List<DisputeRecordDto> disputes = disputeService.listBySegment(s.getId(), s.getPassengerId()).stream()
            .map(BookingController::toDisputeRecordDto)
            .collect(Collectors.toList());
        List<RecomputationRecordDto> recomputations = disputeService.listRecomputationsForSegment(s.getId(), s.getPassengerId()).stream()
            .map(this::toRecomputationRecordDto)
            .collect(Collectors.toList());
        String traceReference = transitions.stream()
            .map(SegmentTransitionDto::getCorrelationId)
            .filter(c -> c != null && !c.isBlank())
            .reduce((first, second) -> second)
            .orElse(null);

        TripSegmentDto.TripSegmentDtoBuilder b = TripSegmentDto.builder()
            .id(s.getId())
            .passengerId(s.getPassengerId())
            .origin(s.getOriginStation())
            .destination(s.getDestinationStation())
            .originStation(s.getOriginStation())
            .destinationStation(s.getDestinationStation())
            .originPlatform(s.getOriginPlatform())
            .destinationPlatform(s.getDestinationPlatform())
            .segmentStartTime(s.getSegmentStartTime())
            .segmentEndTime(s.getSegmentEndTime())
            .fareStatus(s.getFareStatus().name())
            .resolutionDeadline(s.getResolutionDeadline())
            .paidFare(s.getPaidFare())
            .additionalFare(s.getAdditionalFare())
            .penaltyAmount(s.getPenaltyAmount())
            .createdAt(s.getCreatedAt())
            .matchedTripId(s.getMatchedTripId())
            .confidenceScore(s.getConfidenceScore())
            .decisionOutcome(s.getFareStatus().name())
            .decisionBasis("TICKET_VS_SEGMENT")
            .traceReference(traceReference)
            .segmentState(s.getSegmentState() != null ? s.getSegmentState().name() : null)
            .transitions(transitions)
            .disputes(disputes)
            .recomputations(recomputations)
            .hasDisputeLineage(!disputes.isEmpty() || !recomputations.isEmpty());
        if (s.getExplanationJson() != null && !s.getExplanationJson().isBlank()) {
            try {
                Map<String, Object> explanation = objectMapper.readValue(s.getExplanationJson(), new TypeReference<>() { });
                if (!explanation.containsKey("reconstructionConfidence") && explanation.containsKey("computedConfidence")) {
                    explanation.put("reconstructionConfidence", explanation.get("computedConfidence"));
                }
                b.explanation(explanation);
                b.confidenceBand(readString(explanation, "computedConfidence", "band"));
                b.confidenceReasons(readStringList(explanation, "computedConfidence", "breakdown", "reasons"));
                if (traceReference == null || traceReference.isBlank()) {
                    String fallbackTrace = readString(explanation, "journeyConfirmedEventId");
                    if (fallbackTrace == null || fallbackTrace.isBlank()) {
                        fallbackTrace = readString(explanation, "pipelineSource");
                    }
                    b.traceReference(fallbackTrace);
                }
                b.dataQualityScore(readDouble(explanation, "computedConfidence", "breakdown", "temporalScore"));
                List<String> reasons = readStringList(explanation, "computedConfidence", "breakdown", "reasons");
                b.qualityIssues(reasons);
                boolean qualityLimited = reasons.stream().anyMatch(r ->
                    r.toLowerCase().contains("quality")
                        || r.toLowerCase().contains("missing entry")
                        || r.toLowerCase().contains("missing exit")
                        || r.toLowerCase().contains("sparse")
                );
                b.inferenceLimitedByQuality(qualityLimited);
                if (qualityLimited) {
                    b.qualityImpact("Data quality reduced reconstruction certainty; review path may be required.");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> coverage = (Map<String, Object>) readObject(explanation, "fareDecision", "inputs");
                if (coverage != null && !coverage.isEmpty()) {
                    b.coverageCheck(coverage);
                }
            } catch (Exception ignored) {
                // leave explanation null if JSON is corrupt
            }
        }
        return b.build();
    }

    private MovementEventDto toMovementEventDto(MovementEventEntity e) {
        Map<String, Object> payload = null;
        Map<String, Object> explanation = null;
        if (e.getPayloadJson() != null && !e.getPayloadJson().isBlank()) {
            try {
                payload = objectMapper.readValue(e.getPayloadJson(), new TypeReference<>() { });
                if (payload != null) {
                    Object embedded = payload.get("segmentExplanation");
                    if (embedded instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cast = (Map<String, Object>) m;
                        explanation = cast;
                    }
                }
            } catch (Exception ignored) {
                // keep payload/explanation null if JSON is corrupt
            }
        }
        return MovementEventDto.builder()
            .eventId(e.getEventId())
            .userId(e.getUserId())
            .correlationId(e.getCorrelationId())
            .eventType(e.getEventType().name())
            .occurredAt(e.getOccurredAt())
            .recordedAt(e.getRecordedAt())
            .sourceLayer(e.getSourceLayer() != null ? e.getSourceLayer().name() : null)
            .payload(payload)
            .explanation(explanation)
            .build();
    }

    private static SegmentTransitionDto toSegmentTransitionDto(SegmentTransition t) {
        return SegmentTransitionDto.builder()
            .id(t.getId())
            .segmentId(t.getSegmentId())
            .fromState(t.getFromState())
            .toState(t.getToState())
            .triggerEventType(t.getTriggerEventType())
            .reason(t.getReason())
            .occurredAt(t.getOccurredAt())
            .correlationId(t.getCorrelationId())
            .build();
    }

    private List<SegmentTransitionDto> resolveEvidenceTransitions(String correlationId, List<MovementEventDto> timeline) {
        List<SegmentTransition> byCorrelation = segmentTransitionRepository.findByCorrelationIdOrderByOccurredAtAsc(correlationId);
        if (!byCorrelation.isEmpty()) {
            return byCorrelation.stream().map(BookingController::toSegmentTransitionDto).collect(Collectors.toList());
        }
        Set<Long> segmentIds = new LinkedHashSet<>();
        for (MovementEventDto ev : timeline) {
            if (ev.getPayload() == null) continue;
            Object id = ev.getPayload().get("segmentId");
            if (id instanceof Number n) segmentIds.add(n.longValue());
            else if (id instanceof String s) {
                try { segmentIds.add(Long.parseLong(s)); } catch (NumberFormatException ignored) { }
            }
        }
        if (segmentIds.isEmpty()) return List.of();
        return segmentTransitionRepository.findBySegmentIdInOrderByOccurredAtAsc(List.copyOf(segmentIds))
            .stream().map(BookingController::toSegmentTransitionDto).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static String readString(Map<String, Object> root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = ((Map<String, Object>) m).get(p);
        }
        return cur != null ? String.valueOf(cur) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readStringList(Map<String, Object> root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> m)) return List.of();
            cur = ((Map<String, Object>) m).get(p);
        }
        if (!(cur instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Object readObject(Map<String, Object> root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = ((Map<String, Object>) m).get(p);
        }
        return cur;
    }

    private static Double readDouble(Map<String, Object> root, String... path) {
        Object cur = readObject(root, path);
        if (cur instanceof Number n) return n.doubleValue();
        if (cur == null) return null;
        try {
            return Double.parseDouble(String.valueOf(cur));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Builds a small auditable narrative from structured movement events. */
    private static final class EvidenceNarrativeBuilder {
        private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

        private static String ts(Instant i) { return i != null ? TS.format(i) : "—"; }

        static List<String> build(List<MovementEventDto> timeline) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            if (timeline == null || timeline.isEmpty()) {
                out.add("No movement events found for this correlationId.");
                return out;
            }
            MovementEventDto first = timeline.get(0);
            out.add("Trace started for user " + (first.getUserId() != null ? first.getUserId() : "—") + ".");

            for (MovementEventDto ev : timeline) {
                String type = ev.getEventType();
                Instant at = ev.getOccurredAt() != null ? ev.getOccurredAt() : ev.getRecordedAt();
                Map<String, Object> p = ev.getPayload();
                if ("LocationReported".equals(type) && p != null) {
                    out.add("Location reported at " + ts(at) + " (accuracy=" + value(p, "accuracyMeters") + "m).");
                } else if ("GeofenceEntered".equals(type) && p != null) {
                    out.add("User entered station " + value(p, "stationName") + " at " + ts(at) + ".");
                } else if ("GeofenceExited".equals(type) && p != null) {
                    out.add("User exited station " + value(p, "stationName") + " at " + ts(at) + ".");
                } else if ("StationEntryValidated".equals(type) && p != null) {
                    String decision = value(p, "decision");
                    String station = value(p, "stationEntered");
                    String matched = value(p, "matchedReservationId");
                    String rule = nestedValue(p, "explanation", "ruleCode");
                    out.add("Station entry validation at " + ts(at) + ": station=" + station
                        + " decision=" + decision
                        + (matched != null ? " matchedReservationId=" + matched : "")
                        + (rule != null ? " rule=" + rule : "") + ".");
                } else if ("JourneySegmentConfirmed".equals(type) && p != null) {
                    out.add("Journey reconstructed: " + value(p, "originStation") + " → " + value(p, "destinationStation")
                        + " (" + value(p, "segmentStartTime") + " → " + value(p, "segmentEndTime") + ").");
                } else if ("FareValidated".equals(type) && p != null) {
                    out.add("Fare validated at " + ts(at) + ": status=" + value(p, "fareStatus")
                        + " confidence=" + value(p, "confidenceScore")
                        + " passed=" + value(p, "confidencePassed")
                        + " reviewRequired=" + value(p, "reviewRequired")
                        + " rule=" + value(p, "fareRuleCode") + ".");
                } else if ("FraudDecisionMade".equals(type) && p != null) {
                    out.add("Fraud policy decision at " + ts(at) + ": action=" + value(p, "decisionAction")
                        + " rule=" + value(p, "ruleName")
                        + " reason=" + value(p, "decisionReason") + ".");
                }
            }
            return out;
        }

        private static String value(Map<String, Object> map, String key) {
            Object v = map.get(key);
            if (v == null) return null;
            String s = String.valueOf(v);
            return s.isBlank() ? null : s;
        }

        @SuppressWarnings("unchecked")
        private static String nestedValue(Map<String, Object> map, String parentKey, String key) {
            Object parent = map.get(parentKey);
            if (!(parent instanceof Map<?, ?> pm)) return null;
            Object v = ((Map<String, Object>) pm).get(key);
            if (v == null) return null;
            String s = String.valueOf(v);
            return s.isBlank() ? null : s;
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalStateException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
