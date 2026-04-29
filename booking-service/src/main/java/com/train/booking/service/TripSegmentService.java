package com.train.booking.service;
import com.train.booking.config.DetectionRules; // config for detection rule
import com.train.booking.config.RouteOrderConfig;
import com.train.booking.confidence.*;
import com.train.booking.decision.*;
import com.train.booking.domain.*;
import com.train.booking.entitlement.EntitlementResolution;
import com.train.booking.entitlement.EntitlementResolutionService;
import com.train.booking.entitlement.EntitlementState;
import com.train.booking.fare.FarePolicyRulesService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Creates trip segments from geofence events (StationExitDetected → StationEntryDetected)
 * and validates ticket coverage: PAID / UNDERPAID / UNPAID_TRAVEL with idempotent processing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripSegmentService {

    private static final String FARE_EVASION_ACTION = "FARE_EVASION";
    private static final String TICKET_SHARING_ACTION = "TICKET_SHARING";

    @Value("${booking.fare-evasion.resolution-window-minutes:60}") // resolution window for penalty 
    private int resolutionWindowMinutes;

    @Value("${booking.fare-evasion.default-penalty-amount:80}") // demo amt
    private BigDecimal defaultPenaltyAmount;

    @Value("${booking.journey.reconstruction.train-match-window-minutes:5}")
    private int trainMatchWindowMinutes;

    @Value("${booking.journey.reconstruction.confidence-threshold:85}")
    private int confidenceThreshold;

    @Value("${booking.journey.reconstruction.max-travel-time-minutes:120}")
    private int maxTravelTimeMinutes;

    @Value("${booking.journey.reconstruction.confidence-auto-penalty-threshold:90}")
    private int autoPenaltyThreshold;

    @Value("${booking.journey.reconstruction.confidence-review-threshold:70}")
    private int reviewThreshold;

    private final GeofenceEventRepository geofenceEventRepository; 
    private final GeofenceRepository geofenceRepository;
    private final TripSegmentRepository tripSegmentRepository;
    private final ReservationRepository reservationRepository;
    private final TripRepository tripRepository;
    private final RouteOrderConfig routeOrder;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserNotificationRepository userNotificationRepository;
    private final ObjectMapper objectMapper;
    private final ConfidenceScoringService confidenceScoringService;
    private final SegmentDecisionService segmentDecisionService;
    private final SegmentStateMachine segmentStateMachine;
    private final DisputeService disputeService;
    private final FarePolicyRulesService farePolicyRulesService;
    private final EntitlementResolutionService entitlementResolutionService;

   
    @Deprecated(forRemoval = false) // station entry detected
    @Transactional
    public Optional<TripSegment> onStationEntryDetected(String passengerId, String destinationStation, Instant entryTime, Long destinationGeofenceId) {
        return onStationEntryDetected(passengerId, destinationStation, entryTime, destinationGeofenceId, null);
    }

    @Deprecated(forRemoval = false) 
    @Transactional
    public Optional<TripSegment> onStationEntryDetected(String passengerId, String destinationStation, Instant entryTime, Long destinationGeofenceId, Double accuracyDest) {
        Geofence destinationGeofence = destinationGeofenceId != null
            ? geofenceRepository.findById(destinationGeofenceId).orElse(null)
            : null;
        String destinationPlatform = destinationGeofence != null ? destinationGeofence.getPlatform() : null;

        List<GeofenceEvent> exitedEvents = geofenceEventRepository// exit event
            .findExitedEventsByUserIdOrderByCreatedAtDesc(passengerId, PageRequest.of(0, 20));
        for (GeofenceEvent exited : exitedEvents) {
            Geofence originGeofence = exited.getGeofence();
            String originStation = originGeofence.getStationName();
            if (originStation.equalsIgnoreCase(destinationStation)) continue;
            Instant segmentStartTime = exited.getCreatedAt();
            if (segmentStartTime.isAfter(entryTime)) continue;

            long travelMinutes = ChronoUnit.MINUTES.between(segmentStartTime, entryTime);
            if (travelMinutes > maxTravelTimeMinutes) continue; // Step 4: time difference > maxTravelTime → skip segment

            String idempotencyKey = idempotencyKey(passengerId, originStation, destinationStation, segmentStartTime);
            if (tripSegmentRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
                return Optional.empty(); // already processed
            }

            Double accuracyOrigin = exited.getAccuracyMeters();
            String originPlatform = originGeofence.getPlatform();
            BigDecimal routeFare = getFareForRoute(originStation, destinationStation);
            TripSegment segment = buildSegment(passengerId, originStation, destinationStation,
                originPlatform, destinationPlatform, segmentStartTime, entryTime, idempotencyKey, routeFare);
            segment = tripSegmentRepository.save(segment);

            updateMatchAndConfidence(segment, accuracyOrigin, accuracyDest);

            eventPublisher.publishEvent(new TripSegmentCreatedEvent(this, segment));
            logSegment(segment);
            return Optional.of(segment);
        }
        return Optional.empty();
    }

    /**
     * Create a trip segment from a Drools-reconstructed journey (origin → destination, time window).
     * Uses same idempotency and fare logic as onStationEntryDetected. Used by the layered Drools pipeline.
     */
    @Transactional
    public Optional<TripSegment> createSegmentFromJourney(String passengerId, String originStation, String destinationStation,
                                                          Instant segmentStartTime, Instant segmentEndTime,
                                                          Double accuracyOrigin, Double accuracyDest) {
        String idempotencyKey = idempotencyKey(passengerId, originStation, destinationStation, segmentStartTime);
        if (tripSegmentRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return Optional.empty();
        }
        BigDecimal routeFare = getFareForRoute(originStation, destinationStation);
        TripSegment segment = buildSegment(passengerId, originStation, destinationStation,
            null, null, segmentStartTime, segmentEndTime, idempotencyKey, routeFare);
        segment = tripSegmentRepository.save(segment);
        updateMatchAndConfidence(segment, accuracyOrigin, accuracyDest);
        eventPublisher.publishEvent(new TripSegmentCreatedEvent(this, segment));
        logSegment(segment);
        return Optional.of(segment);
    }

    /**
     * Merge central fare-policy fields into {@link TripSegment#getExplanationJson()} (detected journey, pipeline trace).
     */
    @Transactional
    public void mergeCentralPolicyExplanation(Long segmentId, String passengerId, List<String> triggerEventIds,
                                              String pipelineSource, String journeyConfirmedEventId,
                                              boolean reviewRequired, boolean enforcementEligible) {
        TripSegment segment = tripSegmentRepository.findById(segmentId)
            .filter(s -> passengerId.equals(s.getPassengerId()))
            .orElseThrow(() -> new IllegalArgumentException("Segment not found or not yours"));
        Map<String, Object> root = readExplanationRoot(segment);
        root.put("pipelineSource", pipelineSource != null ? pipelineSource : "fare-policy");
        if (triggerEventIds != null && !triggerEventIds.isEmpty()) {
            root.put("triggerEventIds", triggerEventIds);
        }
        if (journeyConfirmedEventId != null) {
            root.put("journeyConfirmedEventId", journeyConfirmedEventId);
        }
        Map<String, Object> detected = new LinkedHashMap<>();
        detected.put("originStation", segment.getOriginStation());
        detected.put("destinationStation", segment.getDestinationStation());
        detected.put("segmentStartTime", segment.getSegmentStartTime());
        detected.put("segmentEndTime", segment.getSegmentEndTime());
        root.put("detectedJourney", detected);
        if (segment.getReservationId() != null) {
            root.put("matchedReservationId", segment.getReservationId());
        }
        root.put("reviewRequired", reviewRequired);
        root.put("enforcementEligible", enforcementEligible);
        Object fare = root.get("fareDecision");
        if (fare instanceof Map<?, ?> fm) {
            Map<String, Object> ticketCoverage = new LinkedHashMap<>();
            ticketCoverage.put("source", "fareDecision.inputs");
            if (fm.get("inputs") != null) ticketCoverage.put("inputs", fm.get("inputs"));
            root.put("ticketCoverage", ticketCoverage);
        }
        Map<String, Object> confidenceBreakdown = new LinkedHashMap<>();
        Object recon = root.get("reconstructionConfidence");
        if (recon instanceof Map<?, ?> rm) {
            confidenceBreakdown.put("reconstructionConfidence", rm);
        }
        if (!confidenceBreakdown.isEmpty()) {
            root.put("confidenceBreakdown", confidenceBreakdown);
        }
        root.put("confidenceScore", segment.getConfidenceScore());
        writeExplanationRoot(segment, root);
        tripSegmentRepository.save(segment);
    }

    /**
     * Step 5–6: Match segment to train schedule (±5 min), compute confidence score (GPS 25%, station 25%, train 30%, duration 20%).
     */
    private void updateMatchAndConfidence(TripSegment segment, Double accuracyOrigin, Double accuracyDest) {
        Instant windowStart = segment.getSegmentStartTime().minus(trainMatchWindowMinutes, ChronoUnit.MINUTES);
        Instant windowEnd = segment.getSegmentStartTime().plus(trainMatchWindowMinutes, ChronoUnit.MINUTES);
        List<Trip> matches = tripRepository.findByFromStationAndToStationAndDepartureTimeBetween(
            segment.getOriginStation(), segment.getDestinationStation(), windowStart, windowEnd);
        if (!matches.isEmpty()) {
            segment.setMatchedTripId(matches.get(0).getId());
        }

        long durationMinutes = ChronoUnit.MINUTES.between(segment.getSegmentStartTime(), segment.getSegmentEndTime());
        boolean routeAligned = routeOrder.indexOf(segment.getOriginStation()) >= 0
            && routeOrder.indexOf(segment.getDestinationStation()) >= 0;
        List<Reservation> internalTickets = reservationRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
            segment.getPassengerId(), List.of(ReservationStatus.CONFIRMED, ReservationStatus.PAID));
        Map<String, Object> rootBeforeConfidence = readExplanationRoot(segment);
        EntitlementResolution entitlementResolution = entitlementResolutionService.resolve(segment, internalTickets, rootBeforeConfidence);
        EntitlementEvidence entitlement = new EntitlementEvidence(
            entitlementResolution.state() != EntitlementState.UNVERIFIED,
            true,
            entitlementResolution.coverage() == CoverageResult.FULL,
            entitlementResolution.coverage() == CoverageResult.PARTIAL
        );
        ConfidenceInput input = new ConfidenceInput(
            new MovementEvidence(true, true, 2, averageAccuracy(accuracyOrigin, accuracyDest)),
            new RouteEvidence(routeAligned, routeAligned, false),
            entitlement,
            new DataQualityEvidence(true, (accuracyOrigin == null && accuracyDest == null) || averageAccuracy(accuracyOrigin, accuracyDest) <= 50.0, (accuracyOrigin == null && accuracyDest == null) ? 70.0 : gpsConfidenceScore(accuracyOrigin, accuracyDest)),
            new AnomalyEvidence(false, false, false, false, durationMinutes > maxTravelTimeMinutes)
        );
        ConfidenceAssessment assessment = confidenceScoringService.assess(input);
        segment.setConfidenceScore(BigDecimal.valueOf(assessment.totalScore()).setScale(2, java.math.RoundingMode.HALF_UP));
        rootBeforeConfidence.put("entitlementResolution", toEntitlementResolutionMap(entitlementResolution));
        writeExplanationRoot(segment, rootBeforeConfidence);
        mergeComputedConfidenceContract(segment, assessment, durationMinutes, windowStart, windowEnd);
        segment = tripSegmentRepository.save(segment);
        applyConfidenceDrivenOutcome(segment, assessment, routeAligned, entitlementResolution);
    }

    /**
     * Confidence policy (explicit, visible):
     *  - >= autoPenaltyThreshold: auto-enforce for no-ticket/route violations
     *  - [reviewThreshold, autoPenaltyThreshold): flag for manual review
     *  - < reviewThreshold: ignore/monitor (still recorded, but no passenger-facing enforcement)
     */
    private void applyConfidenceDrivenOutcome(TripSegment segment, ConfidenceAssessment assessment, boolean routeAligned, EntitlementResolution entitlementResolution) {
        if (segment == null || segment.getFareStatus() == null) return;
        if (segment.getFareStatus() == FareStatus.PAID) return;
        if (segment.getConfidenceScore() == null) return;

        CoverageResult coverage = entitlementResolution != null ? entitlementResolution.coverage() : CoverageResult.NONE;
        boolean unresolvedMissingEvidence = entitlementResolution != null
            ? entitlementResolution.state() == EntitlementState.UNVERIFIED
            : assessment.breakdown().reasons().stream().anyMatch(r -> r.contains("Missing"));
        DecisionContext context = new DecisionContext(
            coverage,
            routeAligned ? RouteValidationResult.VALID : RouteValidationResult.INVALID,
            routeAligned ? FraudRiskResult.LOW : FraudRiskResult.HIGH,
            assessment.totalScore() >= autoPenaltyThreshold ? DataQualityLevel.STRONG
                : assessment.totalScore() >= reviewThreshold ? DataQualityLevel.BORDERLINE : DataQualityLevel.WEAK,
            assessment,
            unresolvedMissingEvidence
        );
        SegmentDecision decision = segmentDecisionService.decide(context);
        SegmentDecisionOutcome outcome = decision.outcome();

        Map<String, Object> root = readExplanationRoot(segment);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyName", "SegmentDecisionTablePolicy");
        policy.put("ruleCode", outcome.name());
        policy.put("decisionSummary", decision.reason());
        policy.put("punitiveAllowed", decision.punitiveAllowed());
        policy.put("confidenceBand", assessment.band().name());
        policy.put("confidenceScore", assessment.totalScore());
        if (entitlementResolution != null) {
            policy.put("entitlementState", entitlementResolution.state().name());
            policy.put("entitlementSource", entitlementResolution.source().name());
            policy.put("entitlementReasons", entitlementResolution.reasons());
        }
        applyOutcomeToSegment(segment, outcome, decision.punitiveAllowed());

        root.put("confidenceOutcome", policy);
        writeExplanationRoot(segment, root);
        tripSegmentRepository.save(segment);
        segmentStateMachine.transition(segment.getId(), toSegmentState(outcome), "SegmentDecisionUpdated", decision.reason(), null);
    }

    private static Map<String, Object> toEntitlementResolutionMap(EntitlementResolution r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) return m;
        m.put("state", r.state().name());
        m.put("source", r.source().name());
        m.put("coverage", r.coverage().name());
        m.put("temporalValid", r.temporalValid());
        m.put("reasons", r.reasons());
        m.put("context", r.context());
        return m;
    }

    private void mergeComputedConfidenceContract(
        TripSegment segment,
        ConfidenceAssessment assessment,
        long durationMinutes,
        Instant scheduleWindowStart,
        Instant scheduleWindowEnd
    ) {
        Map<String, Object> root = readExplanationRoot(segment);
        Map<String, Object> confidenceContract = new LinkedHashMap<>();
        confidenceContract.put("formula", "C=max(0,min(100,0.25G+0.20T+0.20M+0.20R+0.15E-P))");
        confidenceContract.put("score", assessment.totalScore());
        confidenceContract.put("band", assessment.band().name());
        confidenceContract.put("durationMinutes", durationMinutes);
        confidenceContract.put("scheduleWindowStart", scheduleWindowStart);
        confidenceContract.put("scheduleWindowEnd", scheduleWindowEnd);
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("geofenceScore", assessment.breakdown().geofenceScore());
        breakdown.put("temporalScore", assessment.breakdown().temporalScore());
        breakdown.put("movementCompletenessScore", assessment.breakdown().movementCompletenessScore());
        breakdown.put("routeAlignmentScore", assessment.breakdown().routeAlignmentScore());
        breakdown.put("entitlementSupportScore", assessment.breakdown().entitlementSupportScore());
        breakdown.put("penaltyScore", assessment.breakdown().penaltyScore());
        breakdown.put("reasons", assessment.breakdown().reasons());
        confidenceContract.put("breakdown", breakdown);
        root.put("computedConfidence", confidenceContract);
        writeExplanationRoot(segment, root);
    }

    private static double averageAccuracy(Double a, Double b) {
        if (a == null && b == null) return 25.0;
        if (a == null) return b;
        if (b == null) return a;
        return (a + b) / 2.0;
    }

    private void applyOutcomeToSegment(TripSegment segment, SegmentDecisionOutcome outcome, boolean punitiveAllowed) {
        BigDecimal routeFare = getFareForRoute(segment.getOriginStation(), segment.getDestinationStation());
        BigDecimal amount = farePolicyRulesService.computeNoTicketPenalty(routeFare, defaultPenaltyAmount);
        if (outcome == SegmentDecisionOutcome.UNPAID_TRAVEL && punitiveAllowed) {
            segment.setFareStatus(FareStatus.UNPAID_TRAVEL);
            segment.setPenaltyAmount(amount);
            segment.setResolutionDeadline(null);
            return;
        }
        if (outcome == SegmentDecisionOutcome.UNDERPAID) {
            segment.setFareStatus(FareStatus.UNDERPAID);
            return;
        }
        if (outcome == SegmentDecisionOutcome.PAID) {
            segment.setFareStatus(FareStatus.PAID);
            segment.setPenaltyAmount(null);
            segment.setResolutionDeadline(null);
            return;
        }
        segment.setFareStatus(FareStatus.PENDING_REVIEW);
        segment.setPenaltyAmount(null);
        segment.setResolutionDeadline(null);
    }

    private SegmentState toSegmentState(SegmentDecisionOutcome outcome) {
        return switch (outcome) {
            case PAID -> SegmentState.PAID;
            case UNDERPAID -> SegmentState.UNDERPAID;
            case PENDING_REVIEW -> SegmentState.PENDING_REVIEW;
            case PENDING_RESOLUTION -> SegmentState.PENDING_RESOLUTION;
            case UNPAID_TRAVEL -> SegmentState.UNPAID_TRAVEL;
            case ESCALATED_FRAUD_REVIEW -> SegmentState.ESCALATED_FRAUD_REVIEW;
            case OVERTURNED_TO_PAID -> SegmentState.OVERTURNED_TO_PAID;
            case CLOSED -> SegmentState.CLOSED;
        };
    }

    private void mergeReconstructionConfidence(TripSegment segment, Double accuracyOrigin, Double accuracyDest,
                                              double gpsScore, double stationScore, double trainScore, double durationScore,
                                              double confidenceTotal, long durationMinutes,
                                              Instant scheduleWindowStart, Instant scheduleWindowEnd) {
        Map<String, Object> root = readExplanationRoot(segment);
        Map<String, Object> reconstruction = new LinkedHashMap<>();
        reconstruction.put("policyName", "JourneyReconstructionConfidencePolicy");
        reconstruction.put("ruleCode", "SCHEDULE_AND_GPS_WEIGHTED_SCORE");
        reconstruction.put("decisionSummary", String.format(
            "Confidence %.2f (threshold %d): GPS %.0f%%, stations %.0f%%, train match %.0f%%, duration %.0f%%.",
            confidenceTotal, confidenceThreshold, gpsScore, stationScore, trainScore, durationScore));

        Map<String, Object> breakdown = new LinkedHashMap<>();
        Map<String, Object> gps = new LinkedHashMap<>();
        gps.put("weight", 0.25);
        gps.put("score", gpsScore);
        gps.put("accuracyOriginMeters", accuracyOrigin);
        gps.put("accuracyDestMeters", accuracyDest);
        breakdown.put("gps", gps);

        Map<String, Object> stationDetection = new LinkedHashMap<>();
        stationDetection.put("weight", 0.25);
        stationDetection.put("score", stationScore);
        stationDetection.put("reason", "Origin and destination stations inferred from geofence events");
        breakdown.put("stationDetection", stationDetection);

        Map<String, Object> trainMatch = new LinkedHashMap<>();
        trainMatch.put("weight", 0.30);
        trainMatch.put("score", trainScore);
        trainMatch.put("matchedTripId", segment.getMatchedTripId());
        trainMatch.put("windowStart", scheduleWindowStart);
        trainMatch.put("windowEnd", scheduleWindowEnd);
        trainMatch.put("windowMinutes", trainMatchWindowMinutes);
        breakdown.put("trainMatch", trainMatch);

        Map<String, Object> duration = new LinkedHashMap<>();
        duration.put("weight", 0.20);
        duration.put("score", durationScore);
        duration.put("durationMinutes", durationMinutes);
        duration.put("maxTravelTimeMinutes", maxTravelTimeMinutes);
        breakdown.put("duration", duration);
        reconstruction.put("confidenceScore", segment.getConfidenceScore());
        reconstruction.put("confidenceThreshold", confidenceThreshold);
        reconstruction.put("breakdown", breakdown);
        root.put("reconstructionConfidence", reconstruction);
        writeExplanationRoot(segment, root);
    }

    private Map<String, Object> readExplanationRoot(TripSegment segment) {
        if (segment.getExplanationJson() == null || segment.getExplanationJson().isBlank()) {
            return baseExplanationDocument();
        }
        try {
            return objectMapper.readValue(segment.getExplanationJson(), new TypeReference<>() { });
        } catch (Exception e) {
            return baseExplanationDocument();
        }
    }

    private void writeExplanationRoot(TripSegment segment, Map<String, Object> root) {
        try {
            segment.setExplanationJson(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            log.warn("Failed to serialize segment explanation: {}", e.getMessage());
        }
    }

    private Map<String, Object> baseExplanationDocument() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("policyEngine", "booking-service-trip-segment");
        return m;
    }

    private static double gpsConfidenceScore(Double accuracyOrigin, Double accuracyDest) {
        if (accuracyOrigin == null && accuracyDest == null) return 50.0;
        double avg = (accuracyOrigin != null && accuracyDest != null)
            ? (accuracyOrigin + accuracyDest) / 2.0
            : (accuracyOrigin != null ? accuracyOrigin : accuracyDest);
        if (avg <= 20) return 100.0;
        if (avg <= 50) return 80.0;
        if (avg <= 100) return 60.0;
        return 40.0;
    }

    private String idempotencyKey(String passengerId, String origin, String dest, Instant startTime) {
        return passengerId + "|" + origin + "|" + dest + "|" + startTime.getEpochSecond();
    }

    /** Ticket route for coverage: journey (segment) when set, else full trip. */
    private static String ticketFrom(Reservation r) {
        return r.getJourneyFromStation() != null ? r.getJourneyFromStation() : r.getTripSeat().getTrip().getFromStation();
    }

    private static String ticketTo(Reservation r) {
        return r.getJourneyToStation() != null ? r.getJourneyToStation() : r.getTripSeat().getTrip().getToStation();
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

        // Case 1: Full coverage → PAID (store reservationId for ticket-sharing detection)
        
        for (Reservation r : confirmed) {
            String tFrom = ticketFrom(r);
            String tTo = ticketTo(r);
            if (routeOrder.ticketCoversSegment(tFrom, tTo, originStation, destinationStation)) {
                Map<String, Object> fare = new LinkedHashMap<>();
                fare.put("policyName", "FareCoveragePolicy");
                fare.put("ruleCode", "FULL_COVERAGE");
                fare.put("outcome", "ENTITLEMENT_SATISFIED");
                fare.put("decisionSummary", String.format(
                    "Ticket entitlement %s → %s (reservation %d) covers travelled segment %s → %s per route order.",
                    tFrom, tTo, r.getId(), originStation, destinationStation));
                fare.put("inputs", fareCoverageInputs(originStation, destinationStation, r, tFrom, tTo));
                Map<String, Object> root = baseExplanationDocument();
                root.put("fareDecision", fare);
                root.put("ukFareRules", farePolicyRulesService.buildPaidRulesBreakdown(
                    tFrom, tTo, originStation, destinationStation, fullRouteFare, segmentStartTime));
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
                    .reservationId(r.getId())
                    .explanationJson(serializeExplanation(root))
                    .build();
            }
        }

        // Case 2: Short ticket → UNDERPAID (best short ticket = furthest destination on route)
        int so = routeOrder.indexOf(originStation);
        int sd = routeOrder.indexOf(destinationStation);
        BigDecimal bestPaid = BigDecimal.ZERO;
        Reservation bestShort = null;
        for (Reservation r : confirmed) {
            String tFrom = ticketFrom(r);
            String tTo = ticketTo(r);
            int to = routeOrder.indexOf(tFrom);
            int td = routeOrder.indexOf(tTo);
            if (to < 0 || td < 0) continue;
            boolean shortTicket = so <= sd
                ? (to <= so && td < sd && td >= so)
                : (to >= so && td > sd && td <= so);
            if (shortTicket && r.getAmount().compareTo(bestPaid) > 0) {
                bestPaid = r.getAmount();
                bestShort = r;
            }
        }
        if (bestShort != null && bestPaid.compareTo(BigDecimal.ZERO) > 0 && fullRouteFare.compareTo(bestPaid) > 0) {
            String tFrom = ticketFrom(bestShort);
            String tTo = ticketTo(bestShort);
            BigDecimal entitledRouteFare = getFareForRoute(tFrom, tTo);
            BigDecimal additionalRule3 = entitledRouteFare.compareTo(BigDecimal.ZERO) > 0
                ? farePolicyRulesService.computeOvertravelAdjustment(fullRouteFare, entitledRouteFare)
                : BigDecimal.ZERO;
            BigDecimal additionalLegacy = fullRouteFare.subtract(bestPaid).max(BigDecimal.ZERO);
            BigDecimal additional = additionalRule3.max(additionalLegacy);
            auditLogService.log(passengerId, DetectionRules.OVER_TRAVEL,
                String.format("origin=%s dest=%s paidFare=%s additionalFare=%s (travel beyond ticket destination)", originStation, destinationStation, bestPaid, additional));
            Map<String, Object> fare = new LinkedHashMap<>();
            fare.put("policyName", "FareCoveragePolicy");
            fare.put("ruleCode", "SHORT_TICKET_OVER_TRAVEL");
            fare.put("detectionRule", DetectionRules.OVER_TRAVEL);
            fare.put("decisionSummary", String.format(
                "Travelled %s → %s but best matching ticket only covers to %s (paid £%s); route fare £%s → additional £%s.",
                originStation, destinationStation, tTo, bestPaid, fullRouteFare, additional));
            Map<String, Object> inputs = fareCoverageInputs(originStation, destinationStation, bestShort, tFrom, tTo);
            inputs.put("segmentDirection", so <= sd ? "FORWARD" : "BACKWARD");
            inputs.put("fullRouteFare", fullRouteFare);
            inputs.put("entitledRouteFareFromTable", entitledRouteFare);
            inputs.put("additionalFare", additional);
            fare.put("inputs", inputs);
            Map<String, Object> root = baseExplanationDocument();
            root.put("fareDecision", fare);
            root.put("ukFareRules", farePolicyRulesService.buildUnderpaidRulesBreakdown(
                tFrom, tTo, originStation, destinationStation, fullRouteFare, entitledRouteFare, additional, segmentStartTime));
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
                .explanationJson(serializeExplanation(root))
                .build();
        }

        // Case 3: No valid ticket or route violation → PENDING_RESOLUTION (1-hour window), then penalty if unresolved
        boolean segmentOnRoute = routeOrder.indexOf(originStation) >= 0 && routeOrder.indexOf(destinationStation) >= 0;
        String detectionRule = segmentOnRoute ? DetectionRules.NO_TICKET : DetectionRules.ROUTE_VIOLATION;
        Instant resolutionDeadline = Instant.now().plus(resolutionWindowMinutes, ChronoUnit.MINUTES);
        BigDecimal penalty = farePolicyRulesService.computeNoTicketPenalty(fullRouteFare, defaultPenaltyAmount);
        String departureTimeStr = segmentStartTime != null
            ? java.time.ZonedDateTime.ofInstant(segmentStartTime, java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            : "—";
        String message = String.format(
            "We detected that you travelled from %s to %s at %s without a valid ticket. Please resolve this within 1 hour to avoid a penalty.",
            originStation, destinationStation, departureTimeStr);
        auditLogService.log(passengerId, detectionRule,
            String.format("passengerId=%s origin=%s dest=%s fare_status=PENDING_RESOLUTION deadline=%s", passengerId, originStation, destinationStation, resolutionDeadline));
        auditLogService.log(passengerId, FARE_EVASION_ACTION,
            String.format("passengerId=%s origin=%s dest=%s fare_status=PENDING_RESOLUTION deadline=%s", passengerId, originStation, destinationStation, resolutionDeadline));
        userNotificationRepository.save(UserNotification.builder()
            .userId(passengerId)
            .message(message)
            .build());
        Map<String, Object> fare = new LinkedHashMap<>();
        fare.put("policyName", "FareCoveragePolicy");
        fare.put("ruleCode", segmentOnRoute ? "NO_COVERING_TICKET" : "ROUTE_VIOLATION");
        fare.put("detectionRule", detectionRule);
        fare.put("decisionSummary", segmentOnRoute
            ? String.format("No confirmed ticket covers %s → %s; %d entitlement(s) checked against route order.",
                originStation, destinationStation, confirmed.size())
            : String.format("Segment %s → %s is off configured route (station indices: %d → %d).",
                originStation, destinationStation, so, sd));
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("confirmedReservationCount", confirmed.size());
        inputs.put("segmentOnRoute", segmentOnRoute);
        inputs.put("segmentOriginIndex", so);
        inputs.put("segmentDestinationIndex", sd);
        inputs.put("resolutionWindowMinutes", resolutionWindowMinutes);
        inputs.put("resolutionDeadline", resolutionDeadline);
        inputs.put("provisionalPenaltyIfUnpaid", penalty);
        inputs.put("referenceFareForRoute", fullRouteFare);
        fare.put("inputs", inputs);
        Map<String, Object> root = baseExplanationDocument();
        root.put("fareDecision", fare);
        root.put("ukFareRules", farePolicyRulesService.buildNoTicketRulesBreakdown(fullRouteFare, penalty, segmentStartTime));
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
            .explanationJson(serializeExplanation(root))
            .build();
    }

    private Map<String, Object> fareCoverageInputs(String originStation, String destinationStation, Reservation r, String tFrom, String tTo) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("segmentOrigin", originStation);
        inputs.put("segmentDestination", destinationStation);
        inputs.put("ticketOrigin", tFrom);
        inputs.put("ticketDestination", tTo);
        inputs.put("reservationId", r.getId());
        inputs.put("reservationStatus", r.getStatus() != null ? r.getStatus().name() : null);
        if (r.getTripSeat() != null && r.getTripSeat().getTrip() != null) {
            inputs.put("bookedTripId", r.getTripSeat().getTrip().getId());
            inputs.put("bookedTripFrom", r.getTripSeat().getTrip().getFromStation());
            inputs.put("bookedTripTo", r.getTripSeat().getTrip().getToStation());
        }
        inputs.put("journeyFromStation", r.getJourneyFromStation());
        inputs.put("journeyToStation", r.getJourneyToStation());
        inputs.put("entitlementSource", r.getJourneyFromStation() != null ? "JOURNEY_ON_RESERVATION" : "TRIP_ON_RESERVATION");
        inputs.put("segmentOriginIndex", routeOrder.indexOf(originStation));
        inputs.put("segmentDestinationIndex", routeOrder.indexOf(destinationStation));
        inputs.put("ticketOriginIndex", routeOrder.indexOf(tFrom));
        inputs.put("ticketDestinationIndex", routeOrder.indexOf(tTo));
        inputs.put("routeConfigKey", "booking.route.station-order");
        return inputs;
    }

    private String serializeExplanation(Map<String, Object> root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to serialize explanation: {}", e.getMessage());
            return "{\"schemaVersion\":1,\"error\":\"explanation_serialization_failed\"}";
        }
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

    /** Fare evasion cases for admin: unresolved + enforced + review. */
    public List<TripSegment> listFareEvasionCases(int limit) {
        int max = Math.min(Math.max(1, limit), 200);
        return tripSegmentRepository.findByFareStatusInOrderByCreatedAtDesc(
            List.of(FareStatus.PENDING_RESOLUTION, FareStatus.PENDING_REVIEW, FareStatus.UNPAID_TRAVEL), PageRequest.of(0, max));
    }

    /**
     * Admin review workflow for PENDING_REVIEW fare cases.
     * Moves only the segment lifecycle state; fare status remains policy-derived.
     */
    @Transactional
    public Optional<TripSegment> updatePendingReviewState(Long segmentId, SegmentState nextState, String note, String actor) {
        if (nextState == null) {
            throw new IllegalArgumentException("nextState is required");
        }
        if (nextState != SegmentState.PENDING_REVIEW
            && nextState != SegmentState.ESCALATED_FRAUD_REVIEW
            && nextState != SegmentState.CLOSED) {
            throw new IllegalArgumentException("Unsupported review state: " + nextState);
        }
        TripSegment segment = tripSegmentRepository.findById(segmentId)
            .orElseThrow(() -> new IllegalArgumentException("Segment not found: " + segmentId));
        if (segment.getFareStatus() != FareStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Only fareStatus=PENDING_REVIEW can be moved via review workflow");
        }
        String correlationId = java.util.UUID.randomUUID().toString();
        segmentStateMachine.transition(
            segment.getId(),
            nextState,
            "AdminPendingReviewStateChanged",
            (note != null && !note.isBlank()) ? note : ("Admin changed review state to " + nextState),
            correlationId
        );
        TripSegment refreshed = tripSegmentRepository.findById(segment.getId()).orElseThrow();
        Map<String, Object> root = readExplanationRoot(refreshed);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("actor", actor != null && !actor.isBlank() ? actor : "ADMIN");
        review.put("state", nextState.name());
        review.put("note", note);
        review.put("updatedAt", Instant.now().toString());
        review.put("correlationId", correlationId);
        root.put("adminPendingReview", review);
        writeExplanationRoot(refreshed, root);
        tripSegmentRepository.save(refreshed);
        return Optional.of(refreshed);
    }

    /**
     * Passenger disputes UNPAID_TRAVEL/UNDERPAID by proving they had a valid ticket.
     * Validates reservation belongs to user, is CONFIRMED/PAID, and covers the segment route; then updates segment to PAID and waives penalty.
     */
    @Transactional
    public Optional<TripSegment> disputeWithTicket(Long segmentId, String userId, Long reservationId) {
        return disputeService.submitDisputeAndRecompute(segmentId, userId, reservationId,
            "Ticket uploaded by passenger", null);
    }

    /** Apply penalty to PENDING_RESOLUTION segments past their resolution deadline. Run on a schedule. */
    @Transactional
    @Scheduled(fixedDelayString = "${booking.fare-evasion.process-interval-ms:300000}") // default 5 min
    public void processOverdueResolutions() {
        Instant now = Instant.now();
        List<TripSegment> overdue = tripSegmentRepository.findByFareStatusAndResolutionDeadlineBefore(FareStatus.PENDING_RESOLUTION, now);
        for (TripSegment segment : overdue) {
            BigDecimal routeFare = getFareForRoute(segment.getOriginStation(), segment.getDestinationStation());
            BigDecimal amount = farePolicyRulesService.computeNoTicketPenalty(routeFare, defaultPenaltyAmount);
            // Confidence drives outcome: enforce vs review vs monitor.
            if (segment.getConfidenceScore() != null && segment.getConfidenceScore().doubleValue() < autoPenaltyThreshold) {
                segment.setFareStatus(FareStatus.PENDING_REVIEW);
                segment.setPenaltyAmount(null);
                segment.setResolutionDeadline(null);
            } else {
                segment.setFareStatus(FareStatus.UNPAID_TRAVEL);
                segment.setPenaltyAmount(amount);
                segment.setResolutionDeadline(null);
            }

            Map<String, Object> root = readExplanationRoot(segment);
            Map<String, Object> enforcement = new LinkedHashMap<>();
            enforcement.put("policyName", "FareEvasionEnforcementPolicy");
            enforcement.put("ruleCode", "RESOLUTION_WINDOW_EXPIRED");
            enforcement.put("decisionSummary", "Resolution deadline passed without remediation; segment marked UNPAID_TRAVEL with penalty.");
            Map<String, Object> enfInputs = new LinkedHashMap<>();
            enfInputs.put("penaltyAmount", amount);
            enfInputs.put("routeReferenceFare", routeFare);
            enfInputs.put("confidenceScore", segment.getConfidenceScore());
            enforcement.put("inputs", enfInputs);

            boolean enforced = segment.getFareStatus() == FareStatus.UNPAID_TRAVEL;
            if (enforced) {
                enforcement.put("decisionAction", "AUTO_NOTIFY_AND_AUDIT");
                enforcement.put("confidenceGate", Map.of("autoPenaltyThresholdPercent", autoPenaltyThreshold, "passed", true));
            } else {
                enforcement.put("decisionAction", "RECORD_ONLY_LOW_CONFIDENCE");
                enforcement.put("confidenceGate", Map.of(
                    "autoPenaltyThresholdPercent", autoPenaltyThreshold,
                    "passed", false,
                    "reason", "Suppress passenger notification when reconstruction confidence is below auto-penalty threshold"
                ));
            }
            root.put("rule5_confidenceEnforcement", farePolicyRulesService.buildConfidenceEnforcementRule5(
                autoPenaltyThreshold, reviewThreshold, enforced,
                segment.getConfidenceScore() != null ? segment.getConfidenceScore().doubleValue() : null));
            root.put("enforcement", enforcement);
            writeExplanationRoot(segment, root);

            tripSegmentRepository.save(segment);

            if (enforced) {
                auditLogService.log(segment.getPassengerId(), FARE_EVASION_ACTION,
                    String.format("segmentId=%s penalty applied (overdue) amount=%s", segment.getId(), amount));
                userNotificationRepository.save(UserNotification.builder()
                    .userId(segment.getPassengerId())
                    .message(String.format("Penalty Notice: Route %s → %s. Violation: Travel without ticket. Penalty Amount: £%s. Please pay or contact customer services.",
                        segment.getOriginStation(), segment.getDestinationStation(), amount.setScale(2, java.math.RoundingMode.HALF_UP)))
                    .build());
                log.warn("Fare evasion penalty applied: segment {} passenger {} {}→{} £{}", segment.getId(), segment.getPassengerId(),
                    segment.getOriginStation(), segment.getDestinationStation(), amount);
            } else {
                auditLogService.log(segment.getPassengerId(), DetectionRules.LOW_CONFIDENCE,
                    String.format("segmentId=%s confidence=%.0f%%, penalty recorded but no notification (prevent GPS errors)", segment.getId(), segment.getConfidenceScore().doubleValue()));
                auditLogService.log(segment.getPassengerId(), FARE_EVASION_ACTION,
                    String.format("segmentId=%s low confidence (%.0f%%), penalty recorded but no notification", segment.getId(), segment.getConfidenceScore().doubleValue()));
                log.info("Fare evasion low confidence ({}): segment {} {}→{} – penalty recorded, no notification", segment.getConfidenceScore(), segment.getId(), segment.getOriginStation(), segment.getDestinationStation());
            }
        }
    }

    /**
     * Fraud detection: find PAID segments that share the same reservation (ticket) with different passengers and overlapping travel times.
     * Logs TICKET_SHARING to audit and returns the number of reservations flagged.
     */
    @Transactional
    public int detectTicketSharing() {
        List<TripSegment> withReservation = tripSegmentRepository.findAllWithReservationId();
        Map<Long, List<TripSegment>> byReservation = withReservation.stream().collect(Collectors.groupingBy(TripSegment::getReservationId));
        int alerts = 0;
        for (Map.Entry<Long, List<TripSegment>> e : byReservation.entrySet()) {
            List<TripSegment> segments = e.getValue();
            if (segments.size() < 2) continue;
            List<String> distinctPassengers = segments.stream().map(TripSegment::getPassengerId).distinct().collect(Collectors.toList());
            if (distinctPassengers.size() < 2) continue;
            boolean overlapping = false;
            for (int i = 0; i < segments.size() && !overlapping; i++) {
                for (int j = i + 1; j < segments.size(); j++) {
                    TripSegment a = segments.get(i);
                    TripSegment b = segments.get(j);
                    if (!a.getPassengerId().equals(b.getPassengerId())
                        && a.getSegmentStartTime().isBefore(b.getSegmentEndTime())
                        && a.getSegmentEndTime().isAfter(b.getSegmentStartTime())) {
                        overlapping = true;
                        break;
                    }
                }
            }
            if (overlapping) {
                String passengerIds = distinctPassengers.stream().sorted().collect(Collectors.joining(", "));
                String segmentIds = segments.stream().map(s -> String.valueOf(s.getId())).collect(Collectors.joining(", "));
                auditLogService.log("system", DetectionRules.TICKET_SHARING,
                    String.format("reservationId=%s passengerIds=[%s] segmentIds=[%s] (multiple users using one ticket)", e.getKey(), passengerIds, segmentIds));
                auditLogService.log("system", TICKET_SHARING_ACTION,
                    String.format("reservationId=%s passengerIds=[%s] segmentIds=[%s]", e.getKey(), passengerIds, segmentIds));
                log.warn("Ticket sharing detected: reservationId={} passengers={}", e.getKey(), passengerIds);
                alerts++;
            }
        }
        return alerts;
    }

    /** Run ticket-sharing detection hourly (same reservation, different passengers, overlapping times). */
    @Scheduled(cron = "${booking.fraud.ticket-sharing-cron:0 0 * * * *}") // default every hour
    @Transactional
    public void scheduledTicketSharingDetection() {
        int n = detectTicketSharing();
        if (n > 0) log.info("Ticket-sharing detection run: {} reservation(s) flagged", n);
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
