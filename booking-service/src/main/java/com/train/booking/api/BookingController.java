package com.train.booking.api;

import com.train.booking.api.dto.*;
import com.train.booking.domain.RefundRequest;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.StationEntryAction;
import com.train.booking.domain.TicketAlert;
import com.train.booking.domain.Trip;
import com.train.booking.domain.TripSeat;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TicketAlertRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.domain.Geofence;
import com.train.booking.domain.GeofenceEvent;
import com.train.booking.service.AuditLogService;
import com.train.booking.service.AuthService;
import com.train.booking.service.BookingService;
import com.train.booking.domain.UserLocation;
import com.train.booking.domain.UserNotification;
import com.train.booking.repository.UserNotificationRepository;
import com.train.booking.domain.TripSegment;
import com.train.booking.service.GeofenceService;
import com.train.booking.service.LocationService;
import com.train.booking.service.GeofenceRulesService;
import com.train.booking.service.LoadTestService;
import com.train.booking.service.PaymentGatewayService;
import com.train.booking.service.PricingService;
import com.train.booking.service.TripSegmentService;
import com.train.booking.service.RefundService;
import com.train.booking.service.TicketPdfService;
import com.train.booking.service.TripQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000", "capacitor://localhost", "http://localhost"})
public class BookingController {

    private final BookingService bookingService;
    private final TripQueryService tripQueryService;
    private final ReservationRepository reservationRepository;
    private final AuthService authService;
    private final TicketAlertRepository ticketAlertRepository;
    private final TripRepository tripRepository;
    private final AuditLogService auditLogService;
    private final RefundService refundService;
    private final GeofenceService geofenceService;
    private final LocationService locationService;
    private final UserNotificationRepository userNotificationRepository;
    private final GeofenceRulesService geofenceRulesService;
    private final TripSegmentService tripSegmentService;
    private final LoadTestService loadTestService;
    private final PaymentGatewayService paymentGatewayService;
    private final TicketPdfService ticketPdfService;
    private final PricingService pricingService;

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN_HEADER = "X-Admin-Secret";

    @Value("${booking.admin.secret:}")
    private String adminSecret;

    @PostMapping("/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

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
        List<Reservation> reservations = bookingService.reserve(userId, request.getTripId(), request.getSeatIds());
        auditLogService.log(userId, "RESERVE", "tripId=" + request.getTripId() + " seats=" + request.getSeatIds());
        return ResponseEntity.ok(reservations.stream().map(r -> toReservationDto(r)).collect(Collectors.toList()));
    }

    /** Available payment methods (e.g. card/Visa, apple_pay) when gateway is configured. */
    @GetMapping("/payment-methods")
    public List<String> getPaymentMethods() {
        return paymentGatewayService.getAvailablePaymentMethods();
    }

    /** Create payment intent (Stripe): redirect user to gateway. Server verifies via webhook. */
    @PostMapping("/reservations/{reservationId}/create-payment-intent")
    public ResponseEntity<CreatePaymentIntentResponse> createPaymentIntent(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId,
        @Valid @RequestBody CreatePaymentIntentRequest request
    ) {
        PaymentGatewayService.CreatePaymentIntentResult result =
            paymentGatewayService.createPaymentIntent(reservationId, userId, request.getGateway());
        auditLogService.log(userId, "CREATE_PAYMENT_INTENT", "reservationId=" + reservationId + " gateway=" + request.getGateway());
        return ResponseEntity.ok(CreatePaymentIntentResponse.builder()
            .clientSecret(result.getClientSecret())
            .paymentIntentId(result.getPaymentIntentId())
            .amount(result.getAmount())
            .currency(result.getCurrency())
            .build());
    }

    @PostMapping("/reservations/{reservationId}/payment")
    public ResponseEntity<ReservationDto> payment(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId,
        @Valid @RequestBody PaymentRequest request
    ) {
        Reservation r = bookingService.payment(reservationId, userId, request.getPaymentReference());
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

    @PostMapping("/reservations/{reservationId}/refund-request")
    public ResponseEntity<RefundRequestDto> requestRefund(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId
    ) {
        RefundRequest req = refundService.requestRefund(reservationId, userId);
        auditLogService.log(userId, "REFUND_REQUEST", "reservationId=" + reservationId);
        return ResponseEntity.ok(toRefundRequestDto(req));
    }

    @GetMapping("/refund-requests")
    public List<RefundRequestDto> getMyRefundRequests(@RequestHeader(USER_HEADER) String userId) {
        return refundService.findMyRequests(userId).stream()
            .map(this::toRefundRequestDto)
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

    @GetMapping("/admin/refund-requests")
    public List<RefundRequestDto> getRefundRequestsForAdmin(@RequestHeader(ADMIN_HEADER) String adminHeader) {
        requireAdmin(adminHeader);
        return refundService.findAllForAdmin().stream()
            .map(this::toRefundRequestDto)
            .collect(Collectors.toList());
    }

    @PatchMapping("/admin/refund-requests/{requestId}/approve")
    public ResponseEntity<RefundRequestDto> approveRefund(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @PathVariable Long requestId
    ) {
        requireAdmin(adminHeader);
        RefundRequest req = refundService.approve(requestId, "admin");
        auditLogService.log("admin", "APPROVE_REFUND", "requestId=" + requestId + " userId=" + req.getUserId());
        return ResponseEntity.ok(toRefundRequestDto(req));
    }

    @PatchMapping("/admin/refund-requests/{requestId}/reject")
    public ResponseEntity<RefundRequestDto> rejectRefund(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @PathVariable Long requestId
    ) {
        requireAdmin(adminHeader);
        RefundRequest req = refundService.reject(requestId, "admin");
        auditLogService.log("admin", "REJECT_REFUND", "requestId=" + requestId + " userId=" + req.getUserId());
        return ResponseEntity.ok(toRefundRequestDto(req));
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
        GeofenceEvent event = geofenceService.recordEvent(request.getUserId(), request.getGeofenceId(), type);
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
            .map(BookingController::toTripSegmentDto)
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
        TripSegment updated = tripSegmentService.disputeWithTicket(segmentId, userId, request.getReservationId())
            .orElseThrow();
        return ResponseEntity.ok(toTripSegmentDto(updated));
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
        return segments.stream().map(BookingController::toTripSegmentDto).collect(Collectors.toList());
    }

    /** Admin: fare evasion cases (PENDING_RESOLUTION + UNPAID_TRAVEL) for monitoring. */
    @GetMapping("/admin/fare-evasion-cases")
    public List<TripSegmentDto> listFareEvasionCases(
        @RequestHeader(ADMIN_HEADER) String adminHeader,
        @RequestParam(defaultValue = "100") int limit
    ) {
        requireAdmin(adminHeader);
        return tripSegmentService.listFareEvasionCases(limit).stream()
            .map(BookingController::toTripSegmentDto)
            .collect(Collectors.toList());
    }

    @PostMapping("/location")
    public ResponseEntity<UserLocationDto> reportLocation(
        @RequestHeader(USER_HEADER) String userId,
        @Valid @RequestBody ReportLocationRequest request
    ) {
        UserLocation loc = locationService.reportLocation(userId, request.getLatitude(), request.getLongitude());
        return ResponseEntity.ok(toUserLocationDto(loc));
    }

    @GetMapping("/admin/user-locations")
    public List<UserLocationDto> getUserLocations(@RequestHeader(ADMIN_HEADER) String adminHeader) {
        requireAdmin(adminHeader);
        return locationService.getAllUserLocations().stream()
            .map(BookingController::toUserLocationDto)
            .collect(Collectors.toList());
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
            request.getEnterOriginAt(), request.getEnterDestinationAt(), origin.getStationName(), dest.getStationName());
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
            request.getEnterOriginAt(), request.getEnterDestinationAt(), origin.getStationName(), dest.getStationName());
    }

    private ResponseEntity<Map<String, Object>> simulateJourney(String adminHeader, String userId, Long originId, Long destId,
        String enterOriginAt, String enterDestinationAt, String originName, String destName) {
        requireAdmin(adminHeader);
        Instant enterOrigin = java.time.Instant.parse(enterOriginAt);
        Instant enterDest = java.time.Instant.parse(enterDestinationAt);
        Instant exitOrigin = enterOrigin.plusSeconds(60);

        Geofence origin = geofenceService.getGeofenceById(originId);
        Geofence dest = geofenceService.getGeofenceById(destId);

        geofenceService.recordEvent(userId, origin.getId(), GeofenceEvent.EventType.ENTERED, enterOrigin);
        geofenceService.recordEvent(userId, origin.getId(), GeofenceEvent.EventType.EXITED, exitOrigin);
        geofenceService.recordEvent(userId, dest.getId(), GeofenceEvent.EventType.ENTERED, enterDest);

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
        @Valid @RequestBody SendNotificationRequest request
    ) {
        requireAdmin(adminHeader);
        String msg = request.getMessage().length() > 1000 ? request.getMessage().substring(0, 1000) : request.getMessage();
        UserNotification n = UserNotification.builder()
            .userId(request.getUserId())
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
                return ResponseEntity.<Map<String, Object>>ok(Map.of(
                    "valid", true,
                    "reservationId", r.getId(),
                    "fromStation", t != null ? t.getFromStation() : "",
                    "toStation", t != null ? t.getToStation() : "",
                    "departureTime", t != null && t.getDepartureTime() != null ? t.getDepartureTime().toString() : "",
                    "status", r.getStatus().name()
                ));
            })
            .orElse(ResponseEntity.ok(Map.of("valid", false, "message", "Ticket not found or not valid for travel")));
    }

    /** Returns payload for ticket QR (reservationId). Used to display QR for "bought from another" scan flow. */
    @GetMapping("/reservations/{reservationId}/ticket-qr")
    public ResponseEntity<Map<String, Object>> getTicketQrPayload(
        @RequestHeader(USER_HEADER) String userId,
        @PathVariable Long reservationId
    ) {
        return reservationRepository.findByIdAndUserId(reservationId, userId)
            .filter(r -> r.getStatus() == com.train.booking.domain.ReservationStatus.CONFIRMED)
            .map(r -> ResponseEntity.<Map<String, Object>>ok(Map.of("reservationId", r.getId())))
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
            .build();
    }

    private RefundRequestDto toRefundRequestDto(RefundRequest req) {
        ReservationDto resDto = req.getReservationId() != null
            ? reservationRepository.findByIdAndUserIdWithDetails(req.getReservationId(), req.getUserId())
                .map(BookingController::toReservationDto)
                .orElse(null)
            : null;
        return RefundRequestDto.builder()
            .id(req.getId())
            .reservationId(req.getReservationId())
            .userId(req.getUserId())
            .requestedAt(req.getRequestedAt())
            .status(req.getStatus().name())
            .processedAt(req.getProcessedAt())
            .processedBy(req.getProcessedBy())
            .reservation(resDto)
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

    private static TripSegmentDto toTripSegmentDto(TripSegment s) {
        return TripSegmentDto.builder()
            .id(s.getId())
            .passengerId(s.getPassengerId())
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
            .build();
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
