package com.train.booking.service;

import com.train.booking.config.RouteOrderConfig;
import com.train.booking.domain.*;
import com.train.booking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Creates trip segments from geofence events (StationExitDetected → StationEntryDetected)
 * and validates ticket coverage: PAID / UNDERPAID / UNPAID_TRAVEL with idempotent processing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripSegmentService {

    private static final String FARE_EVASION_ACTION = "FARE_EVASION";

    @Value("${booking.fare-evasion.resolution-window-minutes:60}")
    private int resolutionWindowMinutes;

    @Value("${booking.fare-evasion.default-penalty-amount:80}")
    private BigDecimal defaultPenaltyAmount;

    private final GeofenceEventRepository geofenceEventRepository;
    private final GeofenceRepository geofenceRepository;
    private final TripSegmentRepository tripSegmentRepository;
    private final ReservationRepository reservationRepository;
    private final TripRepository tripRepository;
    private final RouteOrderConfig routeOrder;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserNotificationRepository userNotificationRepository;

    /**
     * Called when StationEntryDetected: passenger entered destination geofence (USER_ENTER_PLATFORM).
     * Finds last EXITED (origin), creates trip segment with platform info, validates ticket, sets fare status.
     * If no valid ticket: creates PENDING_RESOLUTION with 1-hour window before penalty.
     */
    @Transactional
    public Optional<TripSegment> onStationEntryDetected(String passengerId, String destinationStation, Instant entryTime, Long destinationGeofenceId) {
        Geofence destinationGeofence = destinationGeofenceId != null
            ? geofenceRepository.findById(destinationGeofenceId).orElse(null)
            : null;
        String destinationPlatform = destinationGeofence != null ? destinationGeofence.getPlatform() : null;

        List<GeofenceEvent> exitedEvents = geofenceEventRepository
            .findExitedEventsByUserIdOrderByCreatedAtDesc(passengerId, PageRequest.of(0, 20));
        for (GeofenceEvent exited : exitedEvents) {
            Geofence originGeofence = exited.getGeofence();
            String originStation = originGeofence.getStationName();
            if (originStation.equalsIgnoreCase(destinationStation)) continue;
            Instant segmentStartTime = exited.getCreatedAt();
            if (segmentStartTime.isAfter(entryTime)) continue;

            String idempotencyKey = idempotencyKey(passengerId, originStation, destinationStation, segmentStartTime);
            if (tripSegmentRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
                return Optional.empty(); // already processed
            }

            String originPlatform = originGeofence.getPlatform();
            BigDecimal routeFare = getFareForRoute(originStation, destinationStation);
            TripSegment segment = buildSegment(passengerId, originStation, destinationStation,
                originPlatform, destinationPlatform, segmentStartTime, entryTime, idempotencyKey, routeFare);
            segment = tripSegmentRepository.save(segment);

            eventPublisher.publishEvent(new TripSegmentCreatedEvent(this, segment));
            logSegment(segment);
            return Optional.of(segment);
        }
        return Optional.empty();
    }

    private String idempotencyKey(String passengerId, String origin, String dest, Instant startTime) {
        return passengerId + "|" + origin + "|" + dest + "|" + startTime.getEpochSecond();
    }

    private BigDecimal getFareForRoute(String fromStation, String toStation) {
        return tripRepository.findByFromStationAndToStation(fromStation, toStation).stream()
            .findFirst()
            .map(Trip::getPricePerSeat)
            .orElse(BigDecimal.ZERO);
    }

    private TripSegment buildSegment(String passengerId, String originStation, String destinationStation,
                                     String originPlatform, String destinationPlatform,
                                     Instant segmentStartTime, Instant segmentEndTime, String idempotencyKey,
                                     BigDecimal fullRouteFare) {
        List<Reservation> confirmed = reservationRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
            passengerId, List.of(ReservationStatus.CONFIRMED, ReservationStatus.PAID));

        // Case 1: Full coverage → PAID
        for (Reservation r : confirmed) {
            String tFrom = r.getTripSeat().getTrip().getFromStation();
            String tTo = r.getTripSeat().getTrip().getToStation();
            if (routeOrder.ticketCoversSegment(tFrom, tTo, originStation, destinationStation)) {
                return TripSegment.builder()
                    .passengerId(passengerId)
                    .originStation(originStation)
                    .destinationStation(destinationStation)
                    .originPlatform(originPlatform)
                    .destinationPlatform(destinationPlatform)
                    .segmentStartTime(segmentStartTime)
                    .segmentEndTime(segmentEndTime)
                    .fareStatus(FareStatus.PAID)
                    .paidFare(fullRouteFare)
                    .additionalFare(null)
                    .penaltyAmount(null)
                    .resolutionDeadline(null)
                    .idempotencyKey(idempotencyKey)
                    .build();
            }
        }

        // Case 2: Short ticket → UNDERPAID (best short ticket = furthest destination on route)
        BigDecimal bestPaid = BigDecimal.ZERO;
        for (Reservation r : confirmed) {
            String tFrom = r.getTripSeat().getTrip().getFromStation();
            String tTo = r.getTripSeat().getTrip().getToStation();
            if (routeOrder.indexOf(tFrom) < 0 || routeOrder.indexOf(tTo) < 0) continue;
            if (routeOrder.indexOf(tFrom) <= routeOrder.indexOf(originStation)
                && routeOrder.indexOf(tTo) < routeOrder.indexOf(destinationStation)
                && routeOrder.indexOf(tTo) >= routeOrder.indexOf(originStation)) {
                if (r.getAmount().compareTo(bestPaid) > 0) bestPaid = r.getAmount();
            }
        }
        if (bestPaid.compareTo(BigDecimal.ZERO) > 0 && fullRouteFare.compareTo(bestPaid) > 0) {
            BigDecimal additional = fullRouteFare.subtract(bestPaid);
            return TripSegment.builder()
                .passengerId(passengerId)
                .originStation(originStation)
                .destinationStation(destinationStation)
                .originPlatform(originPlatform)
                .destinationPlatform(destinationPlatform)
                .segmentStartTime(segmentStartTime)
                .segmentEndTime(segmentEndTime)
                .fareStatus(FareStatus.UNDERPAID)
                .paidFare(bestPaid)
                .additionalFare(additional)
                .penaltyAmount(null)
                .resolutionDeadline(null)
                .idempotencyKey(idempotencyKey)
                .build();
        }

        // Case 3: No valid ticket → PENDING_RESOLUTION (1-hour window), then penalty if unresolved
        Instant resolutionDeadline = Instant.now().plus(resolutionWindowMinutes, ChronoUnit.MINUTES);
        BigDecimal penalty = fullRouteFare.compareTo(BigDecimal.ZERO) > 0 ? fullRouteFare : defaultPenaltyAmount;
        String departureTimeStr = segmentStartTime != null
            ? java.time.ZonedDateTime.ofInstant(segmentStartTime, java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            : "—";
        String message = String.format(
            "We detected that you travelled from %s to %s at %s without a valid ticket. Please resolve this within 1 hour to avoid a penalty.",
            originStation, destinationStation, departureTimeStr);
        auditLogService.log(passengerId, FARE_EVASION_ACTION,
            String.format("passengerId=%s origin=%s dest=%s fare_status=PENDING_RESOLUTION deadline=%s", passengerId, originStation, destinationStation, resolutionDeadline));
        userNotificationRepository.save(UserNotification.builder()
            .userId(passengerId)
            .message(message)
            .build());
        return TripSegment.builder()
            .passengerId(passengerId)
            .originStation(originStation)
            .destinationStation(destinationStation)
            .originPlatform(originPlatform)
            .destinationPlatform(destinationPlatform)
            .segmentStartTime(segmentStartTime)
            .segmentEndTime(segmentEndTime)
            .fareStatus(FareStatus.PENDING_RESOLUTION)
            .paidFare(BigDecimal.ZERO)
            .additionalFare(null)
            .penaltyAmount(null)
            .resolutionDeadline(resolutionDeadline)
            .idempotencyKey(idempotencyKey)
            .build();
    }

    private void logSegment(TripSegment segment) {
        if (segment.getFareStatus() == FareStatus.UNPAID_TRAVEL) {
            log.warn("TripSegment UNPAID_TRAVEL: passenger={} {}→{} penalty={}", segment.getPassengerId(),
                segment.getOriginStation(), segment.getDestinationStation(), segment.getPenaltyAmount());
        } else if (segment.getFareStatus() == FareStatus.UNDERPAID) {
            log.info("TripSegment UNDERPAID: passenger={} {}→{} additionalFare={}", segment.getPassengerId(),
                segment.getOriginStation(), segment.getDestinationStation(), segment.getAdditionalFare());
        } else {
            log.info("TripSegment PAID: passenger={} {}→{}", segment.getPassengerId(),
                segment.getOriginStation(), segment.getDestinationStation());
        }
    }

    public List<TripSegment> listByPassenger(String passengerId, int limit) {
        return tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(passengerId, PageRequest.of(0, limit));
    }

    public List<TripSegment> listAll(int limit) {
        int max = Math.min(Math.max(1, limit), 200);
        return tripSegmentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, max));
    }

    /** Fare evasion cases for admin: PENDING_RESOLUTION and UNPAID_TRAVEL only. */
    public List<TripSegment> listFareEvasionCases(int limit) {
        int max = Math.min(Math.max(1, limit), 200);
        return tripSegmentRepository.findByFareStatusInOrderByCreatedAtDesc(
            List.of(FareStatus.PENDING_RESOLUTION, FareStatus.UNPAID_TRAVEL), PageRequest.of(0, max));
    }

    /**
     * Passenger disputes UNPAID_TRAVEL/UNDERPAID by proving they had a valid ticket.
     * Validates reservation belongs to user, is CONFIRMED/PAID, and covers the segment route; then updates segment to PAID and waives penalty.
     */
    @Transactional
    public Optional<TripSegment> disputeWithTicket(Long segmentId, String userId, Long reservationId) {
        TripSegment segment = tripSegmentRepository.findById(segmentId)
            .filter(s -> userId.equals(s.getPassengerId()))
            .orElseThrow(() -> new IllegalArgumentException("Segment not found or not yours"));
        if (segment.getFareStatus() != FareStatus.UNPAID_TRAVEL && segment.getFareStatus() != FareStatus.UNDERPAID
            && segment.getFareStatus() != FareStatus.PENDING_RESOLUTION) {
            throw new IllegalStateException("This journey is already marked as paid or cannot be disputed");
        }
        Reservation r = reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Ticket not found or not yours"));
        if (r.getStatus() != ReservationStatus.CONFIRMED && r.getStatus() != ReservationStatus.PAID) {
            throw new IllegalStateException("Ticket is not valid for travel (must be confirmed or paid)");
        }
        String tFrom = r.getTripSeat().getTrip().getFromStation();
        String tTo = r.getTripSeat().getTrip().getToStation();
        if (!routeOrder.ticketCoversSegment(tFrom, tTo, segment.getOriginStation(), segment.getDestinationStation())) {
            throw new IllegalArgumentException("This ticket does not cover the journey " + segment.getOriginStation() + " → " + segment.getDestinationStation());
        }
        BigDecimal routeFare = getFareForRoute(segment.getOriginStation(), segment.getDestinationStation());
        segment.setFareStatus(FareStatus.PAID);
        segment.setPaidFare(routeFare);
        segment.setAdditionalFare(null);
        segment.setPenaltyAmount(null);
        segment.setResolutionDeadline(null);
        segment = tripSegmentRepository.save(segment);
        userNotificationRepository.save(UserNotification.builder()
            .userId(userId)
            .message("Your ticket was accepted for " + segment.getOriginStation() + " → " + segment.getDestinationStation() + ". Penalty waived.")
            .build());
        log.info("Segment {} disputed with reservation {} by user {}; status set to PAID", segmentId, reservationId, userId);
        return Optional.of(segment);
    }

    /** Apply penalty to PENDING_RESOLUTION segments past their resolution deadline. Run on a schedule. */
    @Transactional
    @Scheduled(fixedDelayString = "${booking.fare-evasion.process-interval-ms:300000}") // default 5 min
    public void processOverdueResolutions() {
        Instant now = Instant.now();
        List<TripSegment> overdue = tripSegmentRepository.findByFareStatusAndResolutionDeadlineBefore(FareStatus.PENDING_RESOLUTION, now);
        BigDecimal penalty = defaultPenaltyAmount;
        for (TripSegment segment : overdue) {
            BigDecimal routeFare = getFareForRoute(segment.getOriginStation(), segment.getDestinationStation());
            BigDecimal amount = routeFare.compareTo(BigDecimal.ZERO) > 0 ? routeFare : penalty;
            segment.setFareStatus(FareStatus.UNPAID_TRAVEL);
            segment.setPenaltyAmount(amount);
            segment.setResolutionDeadline(null);
            tripSegmentRepository.save(segment);
            auditLogService.log(segment.getPassengerId(), FARE_EVASION_ACTION,
                String.format("segmentId=%s penalty applied (overdue) amount=%s", segment.getId(), amount));
            userNotificationRepository.save(UserNotification.builder()
                .userId(segment.getPassengerId())
                .message(String.format("Penalty Notice: Route %s → %s. Violation: Travel without ticket. Penalty Amount: £%s. Please pay or contact customer services.",
                    segment.getOriginStation(), segment.getDestinationStation(), amount.setScale(2, java.math.RoundingMode.HALF_UP)))
                .build());
            log.warn("Fare evasion penalty applied: segment {} passenger {} {}→{} £{}", segment.getId(), segment.getPassengerId(),
                segment.getOriginStation(), segment.getDestinationStation(), amount);
        }
    }

    /** Application event for downstream consumers (e.g. billing, fraud detection). */
    public static class TripSegmentCreatedEvent extends org.springframework.context.ApplicationEvent {
        private final TripSegment segment;
        public TripSegmentCreatedEvent(Object source, TripSegment segment) {
            super(source);
            this.segment = segment;
        }
        public TripSegment getSegment() { return segment; }
    }
}
