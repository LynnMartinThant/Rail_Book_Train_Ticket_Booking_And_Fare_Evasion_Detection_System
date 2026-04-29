package com.train.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.train.booking.domain.*;
import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.platform.MovementSourceLayer;
import com.train.booking.repository.DisputeRecordRepository;
import com.train.booking.repository.RecomputationRecordRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.repository.TripSegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeService {

    private final TripSegmentRepository tripSegmentRepository;
    private final ReservationRepository reservationRepository;
    private final TripRepository tripRepository;
    private final DisputeRecordRepository disputeRecordRepository;
    private final RecomputationRecordRepository recomputationRecordRepository;
    private final SegmentStateMachine segmentStateMachine;
    private final MovementEventWriter movementEventWriter;
    private final ObjectMapper objectMapper;

    @Transactional
    public Optional<TripSegment> submitDisputeAndRecompute(
        Long segmentId,
        String userId,
        Long reservationId,
        String reason,
        String evidenceReference
    ) {
        TripSegment segment = tripSegmentRepository.findById(segmentId)
            .filter(s -> userId.equals(s.getPassengerId()))
            .orElseThrow(() -> new IllegalArgumentException("Segment not found or not yours"));
        if (segment.getFareStatus() != FareStatus.UNPAID_TRAVEL && segment.getFareStatus() != FareStatus.UNDERPAID
            && segment.getFareStatus() != FareStatus.PENDING_RESOLUTION
            && segment.getFareStatus() != FareStatus.PENDING_REVIEW) {
            throw new IllegalStateException("This journey is already marked as paid or cannot be disputed");
        }
        Reservation reservation = reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Ticket not found or not yours"));
        if (reservation.getStatus() != ReservationStatus.CONFIRMED && reservation.getStatus() != ReservationStatus.PAID) {
            throw new IllegalStateException("Ticket is not valid for travel (must be confirmed or paid)");
        }

        String correlationId = java.util.UUID.randomUUID().toString();
        String previousDecision = segment.getFareStatus().name();
        BigDecimal previousConfidence = segment.getConfidenceScore();
        Map<String, Object> originalExplanation = readExplanation(segment.getExplanationJson());

        DisputeRecord dispute = disputeRecordRepository.save(DisputeRecord.builder()
            .segmentId(segment.getId())
            .userId(userId)
            .reason(reason)
            .evidenceReference(evidenceReference)
            .status(DisputeStatus.OPEN)
            .build());
        segmentStateMachine.transition(segment.getId(), SegmentState.DISPUTED, "DisputeSubmitted",
            "Passenger submitted dispute proof", correlationId);
        Map<String, Object> disputePayload = new LinkedHashMap<>();
        disputePayload.put("segmentId", segmentId);
        disputePayload.put("disputeId", dispute.getId());
        disputePayload.put("reason", reason);
        disputePayload.put("evidenceReference", evidenceReference);
        movementEventWriter.append(userId, correlationId, MovementEventType.DisputeSubmitted, Instant.now(),
            disputePayload, MovementSourceLayer.ADMIN_SUPERVISION);

        dispute.setStatus(DisputeStatus.UNDER_REVIEW);
        disputeRecordRepository.save(dispute);
        segmentStateMachine.transition(segment.getId(), SegmentState.RECOMPUTING, "RecomputationStarted",
            "Recomputation started for dispute " + dispute.getId(), correlationId);

        boolean coverageValid = ticketCoversSegment(reservation, segment);
        double recomputedConfidence = computeRecomputedConfidence(segment, previousConfidence, coverageValid, evidenceReference);
        String recomputedDecision = (coverageValid && recomputedConfidence >= 85.0)
            ? SegmentState.OVERTURNED_TO_PAID.name()
            : SegmentState.CLOSED.name();

        Map<String, Object> recomputeExplanation = new LinkedHashMap<>();
        recomputeExplanation.put("policyName", "DisputeRecomputationPolicy");
        recomputeExplanation.put("ruleCode", coverageValid ? "PROOF_ACCEPTED" : "PROOF_REJECTED");
        recomputeExplanation.put("decisionReason", coverageValid
            ? "Newly supplied proof covers the disputed segment and confidence is enforcement-safe."
            : "Supplied proof does not cover the disputed segment or confidence remains insufficient.");
        Map<String, Object> recomputeInputs = new LinkedHashMap<>();
        recomputeInputs.put("segmentId", segmentId);
        recomputeInputs.put("reservationId", reservationId);
        recomputeInputs.put("previousDecision", previousDecision);
        recomputeInputs.put("previousConfidence", previousConfidence);
        recomputeInputs.put("evidenceReference", evidenceReference);
        recomputeExplanation.put("inputs", recomputeInputs);
        recomputeExplanation.put("recomputedConfidence", recomputedConfidence);
        recomputeExplanation.put("recomputedDecision", recomputedDecision);
        recomputeExplanation.put("originalExplanationSnapshot", originalExplanation);

        recomputationRecordRepository.save(RecomputationRecord.builder()
            .segmentId(segmentId)
            .disputeId(dispute.getId())
            .previousDecision(previousDecision)
            .recomputedDecision(recomputedDecision)
            .recomputedConfidence(recomputedConfidence)
            .explanationJson(writeJson(recomputeExplanation))
            .build());

        Map<String, Object> segmentExplanation = readExplanation(segment.getExplanationJson());
        List<Map<String, Object>> lineage = readLineage(segmentExplanation);
        lineage.add(Map.of(
            "timestamp", Instant.now().toString(),
            "previousDecision", previousDecision,
            "disputeId", dispute.getId().toString(),
            "recomputedDecision", recomputedDecision,
            "recomputedConfidence", recomputedConfidence
        ));
        segmentExplanation.put("decisionLineage", lineage);
        segmentExplanation.put("disputeResolution", recomputeExplanation);
        segment.setExplanationJson(writeJson(segmentExplanation));

        if (SegmentState.OVERTURNED_TO_PAID.name().equals(recomputedDecision)) {
            segment.setFareStatus(FareStatus.PAID);
            segment.setPaidFare(getFareForRoute(segment.getOriginStation(), segment.getDestinationStation()));
            segment.setAdditionalFare(null);
            segment.setPenaltyAmount(null);
            segment.setResolutionDeadline(null);
            segment.setReservationId(reservationId);
            dispute.setStatus(DisputeStatus.ACCEPTED);
            dispute.setDecidedAt(Instant.now());
            dispute.setDecidedBy("SYSTEM_RECOMPUTATION");
            segmentStateMachine.transition(segment.getId(), SegmentState.OVERTURNED_TO_PAID, "RecomputationCompleted",
                "Dispute accepted after recomputation", correlationId);
        } else {
            dispute.setStatus(DisputeStatus.REJECTED);
            dispute.setDecidedAt(Instant.now());
            dispute.setDecidedBy("SYSTEM_RECOMPUTATION");
            segmentStateMachine.transition(segment.getId(), SegmentState.CLOSED, "RecomputationCompleted",
                "Dispute rejected after recomputation", correlationId);
        }
        disputeRecordRepository.save(dispute);
        tripSegmentRepository.save(segment);
        return Optional.of(segment);
    }

    public List<DisputeRecord> listBySegment(Long segmentId, String userId) {
        return disputeRecordRepository.findBySegmentIdOrderBySubmittedAtDesc(segmentId).stream()
            .filter(d -> userId.equals(d.getUserId()))
            .toList();
    }

    public List<DisputeRecord> listByStatus(DisputeStatus status) {
        return status != null
            ? disputeRecordRepository.findByStatusOrderBySubmittedAtDesc(status)
            : disputeRecordRepository.findAll();
    }

    @Transactional
    public DisputeRecord markUnderReview(UUID disputeId, String decidedBy) {
        DisputeRecord dispute = findDispute(disputeId);
        if (dispute.getStatus() == DisputeStatus.ACCEPTED || dispute.getStatus() == DisputeStatus.REJECTED) {
            throw new IllegalStateException("Finalised dispute cannot be moved back to review");
        }
        dispute.setStatus(DisputeStatus.UNDER_REVIEW);
        dispute.setDecidedAt(null);
        dispute.setDecidedBy(decidedBy);
        return disputeRecordRepository.save(dispute);
    }

    @Transactional
    public DisputeRecord acceptDispute(UUID disputeId, String decidedBy) {
        DisputeRecord dispute = findDispute(disputeId);
        dispute.setStatus(DisputeStatus.ACCEPTED);
        dispute.setDecidedAt(Instant.now());
        dispute.setDecidedBy(decidedBy);
        return disputeRecordRepository.save(dispute);
    }

    @Transactional
    public DisputeRecord rejectDispute(UUID disputeId, String decidedBy) {
        DisputeRecord dispute = findDispute(disputeId);
        dispute.setStatus(DisputeStatus.REJECTED);
        dispute.setDecidedAt(Instant.now());
        dispute.setDecidedBy(decidedBy);
        return disputeRecordRepository.save(dispute);
    }

    public List<RecomputationRecord> listRecomputationsForSegment(Long segmentId, String userId) {
        tripSegmentRepository.findById(segmentId)
            .filter(s -> userId.equals(s.getPassengerId()))
            .orElseThrow(() -> new IllegalArgumentException("Segment not found or not yours"));
        return recomputationRecordRepository.findBySegmentIdOrderByRecomputedAtDesc(segmentId);
    }

    private boolean ticketCoversSegment(Reservation r, TripSegment segment) {
        String tFrom = r.getJourneyFromStation() != null ? r.getJourneyFromStation() : r.getTripSeat().getTrip().getFromStation();
        String tTo = r.getJourneyToStation() != null ? r.getJourneyToStation() : r.getTripSeat().getTrip().getToStation();
        if (tFrom == null || tTo == null) return false;
        return tFrom.equalsIgnoreCase(segment.getOriginStation()) && tTo.equalsIgnoreCase(segment.getDestinationStation());
    }

    private double computeRecomputedConfidence(TripSegment segment, BigDecimal prev, boolean coverageValid, String evidenceReference) {
        double base = prev != null ? prev.doubleValue() : 70.0;
        if (coverageValid) base = Math.max(base, 85.0) + 8.0;
        if (evidenceReference != null && !evidenceReference.isBlank()) base += 3.0;
        if (segment.getMatchedTripId() != null) base += 2.0;
        return Math.max(0.0, Math.min(100.0, base));
    }

    private BigDecimal getFareForRoute(String fromStation, String toStation) {
        return tripRepository.findByFromStationAndToStation(fromStation, toStation).stream()
            .findFirst()
            .map(Trip::getPricePerSeat)
            .orElse(BigDecimal.ZERO);
    }

    private Map<String, Object> readExplanation(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readLineage(Map<String, Object> root) {
        Object raw = root.get("decisionLineage");
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return new java.util.ArrayList<>();
    }

    private String writeJson(Map<String, Object> root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to write dispute JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private DisputeRecord findDispute(UUID disputeId) {
        return disputeRecordRepository.findById(disputeId)
            .orElseThrow(() -> new IllegalArgumentException("Dispute not found"));
    }
}

