package com.train.booking.service;

import com.train.booking.config.DetectionRules;
import com.train.booking.domain.FareStatus;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.TripSegment;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TripSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fraud Detection Engine: orchestrates all fraud checks and system-reliability consistency.
 * <ul>
 *   <li>Ticket Sharing – same reservation, different passengers, overlapping times (delegates to TripSegmentService)</li>
 *   <li>Refund Fraud – disabled when refunds are not part of the deployed product</li>
 *   <li>Multiple Device Usage – same user with overlapping journey segments in different locations (impossible journey)</li>
 *   <li>Payment Chargeback – logged via audit when gateway reports chargeback (handled in PaymentGatewayService)</li>
 *   <li>Suspicious Pattern (abnormal travel behavior) – high fare evasion count or many PENDING_RESOLUTION segments</li>
 *   <li>System Reliability – consistency check: orphan reservations, segments with refunded ticket</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {

    public static final String FRAUD_TICKET_SHARING = "FRAUD_TICKET_SHARING";
    public static final String FRAUD_REFUND_ABUSE = "FRAUD_REFUND_ABUSE";
    public static final String FRAUD_REFUND_AFTER_TRAVEL = "FRAUD_REFUND_AFTER_TRAVEL";
    public static final String FRAUD_MULTIPLE_DEVICE = "FRAUD_MULTIPLE_DEVICE";
    public static final String FRAUD_CHARGEBACK = "FRAUD_CHARGEBACK";
    public static final String FRAUD_SUSPICIOUS_ACCOUNT = "FRAUD_SUSPICIOUS_ACCOUNT";
    public static final String FRAUD_CONSISTENCY_ORPHAN = "FRAUD_CONSISTENCY_ORPHAN";
    public static final String FRAUD_CONSISTENCY_REFUNDED_TICKET = "FRAUD_CONSISTENCY_REFUNDED_TICKET";

    @Value("${booking.fraud.suspicious-account-min-evasion-count:3}")
    private int suspiciousAccountMinEvasionCount;

    @Value("${booking.fraud.suspicious-account-min-pending-resolution:2}")
    private int suspiciousAccountMinPendingResolution;

    private final TripSegmentService tripSegmentService;
    private final AuditLogService auditLogService;
    private final TripSegmentRepository tripSegmentRepository;
    private final ReservationRepository reservationRepository;

    /**
     * Run all fraud checks and consistency checks. Returns summary counts per type.
     */
    @Transactional
    public FraudDetectionResult runAll() {
        int ticketSharing = runTicketSharingDetection();
        int refundFraud = runRefundFraudDetection();
        int multipleDevice = runMultipleDeviceDetection();
        int suspiciousAccount = runSuspiciousAccountDetection();
        int consistency = runConsistencyCheck();
        return FraudDetectionResult.builder()
            .ticketSharingAlerts(ticketSharing)
            .refundFraudAlerts(refundFraud)
            .multipleDeviceAlerts(multipleDevice)
            .suspiciousAccountAlerts(suspiciousAccount)
            .consistencyAlerts(consistency)
            .runAt(Instant.now())
            .build();
    }

    /** Ticket sharing: same reservation, different passengers, overlapping journey times. */
    public int runTicketSharingDetection() {
        return tripSegmentService.detectTicketSharing();
    }

    /** Refund fraud detection is disabled when refund flows are removed from the product. */
    public int runRefundFraudDetection() {
        return 0;
    }

    /** Multiple device / impossible journey: same user, two segments with overlapping time and different stations. */
    public int runMultipleDeviceDetection() {
        int alerts = 0;
        List<TripSegment> segments = tripSegmentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000));
        Map<String, List<TripSegment>> byPassenger = segments.stream().collect(Collectors.groupingBy(TripSegment::getPassengerId));
        for (Map.Entry<String, List<TripSegment>> e : byPassenger.entrySet()) {
            List<TripSegment> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    TripSegment a = list.get(i);
                    TripSegment b = list.get(j);
                    if (a.getSegmentStartTime().isBefore(b.getSegmentEndTime()) && a.getSegmentEndTime().isAfter(b.getSegmentStartTime())) {
                        boolean sameRoute = a.getOriginStation().equals(b.getOriginStation()) && a.getDestinationStation().equals(b.getDestinationStation());
                        if (!sameRoute) {
                            auditLogService.log(a.getPassengerId(), FRAUD_MULTIPLE_DEVICE,
                                "segmentIds=" + a.getId() + "," + b.getId() + " overlapping times, different routes");
                            alerts++;
                            break; // one alert per user pair
                        }
                    }
                }
            }
        }
        return alerts;
    }

    /** Suspicious account: user with many FARE_EVASION audit entries or many PENDING_RESOLUTION segments. */
    public int runSuspiciousAccountDetection() {
        int alerts = 0;
        List<String> evasionUserIds = auditLogService.findByAction("FARE_EVASION", 500).stream()
            .map(com.train.booking.domain.AuditLog::getUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        Map<String, Long> evasionCount = evasionUserIds.stream().collect(Collectors.groupingBy(u -> u, Collectors.counting()));
        for (Map.Entry<String, Long> e : evasionCount.entrySet()) {
            if (e.getValue() >= suspiciousAccountMinEvasionCount) {
                auditLogService.log(e.getKey(), DetectionRules.SUSPICIOUS_PATTERN, "abnormal travel: fareEvasionCount=" + e.getValue());
                auditLogService.log(e.getKey(), FRAUD_SUSPICIOUS_ACCOUNT, "fareEvasionCount=" + e.getValue());
                alerts++;
            }
        }
        List<TripSegment> pending = tripSegmentRepository.findByFareStatusInOrderByCreatedAtDesc(
            List.of(FareStatus.PENDING_RESOLUTION, FareStatus.PENDING_REVIEW), PageRequest.of(0, 500));
        Map<String, Long> pendingByUser = pending.stream().collect(Collectors.groupingBy(TripSegment::getPassengerId, Collectors.counting()));
        for (Map.Entry<String, Long> e : pendingByUser.entrySet()) {
            if (e.getValue() >= suspiciousAccountMinPendingResolution && !evasionCount.containsKey(e.getKey())) {
                auditLogService.log(e.getKey(), DetectionRules.SUSPICIOUS_PATTERN, "abnormal travel: pendingResolutionSegments=" + e.getValue());
                auditLogService.log(e.getKey(), FRAUD_SUSPICIOUS_ACCOUNT, "pendingResolutionSegments=" + e.getValue());
                alerts++;
            }
        }
        return alerts;
    }

    /** System reliability: consistency – orphan CONFIRMED/PAID reservations (trip in past, no segment); segments with refunded reservation. */
    public int runConsistencyCheck() {
        int alerts = 0;
        Instant now = Instant.now();
        List<Reservation> confirmed = reservationRepository.findByStatusInOrderByCreatedAtDesc(
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.PAID), PageRequest.of(0, 200));
        for (Reservation r : confirmed) {
            if (r.getTripSeat().getTrip().getDepartureTime().isBefore(now)) {
                List<TripSegment> segs = tripSegmentRepository.findByReservationId(r.getId());
                if (segs.isEmpty()) {
                    auditLogService.log("system", FRAUD_CONSISTENCY_ORPHAN,
                        "reservationId=" + r.getId() + " userId=" + r.getUserId() + " tripInPast no segment");
                    alerts++;
                }
            }
        }
        List<TripSegment> withRes = tripSegmentRepository.findAllWithReservationId();
        for (TripSegment s : withRes) {
            if (s.getReservationId() == null) continue;
            Optional<Reservation> ro = reservationRepository.findById(s.getReservationId());
            if (ro.isPresent() && ro.get().getStatus() == ReservationStatus.REFUNDED) {
                auditLogService.log("system", FRAUD_CONSISTENCY_REFUNDED_TICKET,
                    "segmentId=" + s.getId() + " reservationId=" + s.getReservationId() + " status=REFUNDED");
                alerts++;
            }
        }
        return alerts;
    }

    @Scheduled(cron = "${booking.fraud.cron:0 0 * * * *}")
    public void scheduledFraudDetection() {
        try {
            FraudDetectionResult r = runAll();
            int total = r.getTicketSharingAlerts() + r.getRefundFraudAlerts() + r.getMultipleDeviceAlerts()
                + r.getSuspiciousAccountAlerts() + r.getConsistencyAlerts();
            if (total > 0) log.info("Fraud detection run: {} total alerts (ticketSharing={} refund={} multiDevice={} suspicious={} consistency={})",
                total, r.getTicketSharingAlerts(), r.getRefundFraudAlerts(), r.getMultipleDeviceAlerts(),
                r.getSuspiciousAccountAlerts(), r.getConsistencyAlerts());
        } catch (Exception e) {
            log.warn("Fraud detection run failed: {}", e.getMessage());
        }
    }

    /** Call from payment gateway when chargeback is received (e.g. Stripe dispute). */
    public void recordChargeback(String userId, String reservationIdOrRef, String details) {
        auditLogService.log(userId != null ? userId : "system", FRAUD_CHARGEBACK,
            "reservationOrRef=" + reservationIdOrRef + " " + (details != null ? details : ""));
    }

    @lombok.Data
    @lombok.Builder
    public static class FraudDetectionResult {
        private int ticketSharingAlerts;
        private int refundFraudAlerts;
        private int multipleDeviceAlerts;
        private int suspiciousAccountAlerts;
        private int consistencyAlerts;
        private Instant runAt;
    }
}
