package com.train.booking.platform.farepolicy;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.train.booking.domain.AuditDecisionRecord;
import com.train.booking.domain.FareStatus;
import com.train.booking.domain.TripSegment;
import com.train.booking.movement.eventlog.MovementEventEnvelope;
import com.train.booking.movement.eventlog.MovementEventRecordedEvent;
import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.platform.MovementSourceLayer;
import com.train.booking.platform.observability.PlatformTraceLog;
import com.train.booking.repository.AuditDecisionRecordRepository;
import com.train.booking.repository.TripSegmentRepository;
import com.train.booking.service.TripSegmentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central fare policy: consumes confirmed journey segments only; emits {@link MovementEventType#FareValidated}.
 * Does not process raw GPS.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FarePolicyConsumer {

    private final TripSegmentService tripSegmentService;
    private final MovementEventWriter movementEventWriter;
    private final MovementPipelineMetrics metrics;
    private final ObjectMapper objectMapper;
    private final AuditDecisionRecordRepository auditDecisionRecordRepository;
    private final TripSegmentRepository tripSegmentRepository;

    @Value("${booking.journey.reconstruction.confidence-threshold:75}")
    private int confidenceThreshold;

    @EventListener
    @Transactional
    public void onMovementEvent(MovementEventRecordedEvent evt) {
        MovementEventEnvelope e = evt.getEnvelope();
        if (e == null || e.getUserId() == null || e.getEventType() == null) return;
        if (!"JourneySegmentConfirmed".equals(e.getEventType())) return;

        handleJourneySegmentConfirmed(e);
    }

    private void handleJourneySegmentConfirmed(MovementEventEnvelope e) {
        Instant started = Instant.now();
        if (!(e.getPayload() instanceof Map<?, ?> payload)) return;
        String origin = value(payload, "originStation");
        String dest = value(payload, "destinationStation");
        Instant start = instant(payload.get("segmentStartTime"), e.getOccurredAt());
        Instant end = instant(payload.get("segmentEndTime"), e.getRecordedAt());
        if (origin == null || dest == null || start == null || end == null) return;

        PlatformTraceLog.info(log, "fare-policy", "JourneySegmentConfirmed", "received",
            e.getCorrelationId(), e.getEventId(), e.getUserId());

        Optional<TripSegment> created = tripSegmentService.createSegmentFromJourney(
            e.getUserId(),
            origin,
            dest,
            start,
            end,
            null,
            null
        );
        if (created.isEmpty()) return;

        TripSegment segment = created.get();
        boolean confidencePassed = segment.getConfidenceScore() == null
            || segment.getConfidenceScore().compareTo(java.math.BigDecimal.valueOf(confidenceThreshold)) >= 0;
        boolean reviewRequired = segment.getFareStatus() != FareStatus.PAID || !confidencePassed;
        boolean enforcementEligible = confidencePassed;

        List<String> triggers = new ArrayList<>();
        triggers.add(e.getEventId());
        tripSegmentService.mergeCentralPolicyExplanation(
            segment.getId(),
            e.getUserId(),
            triggers,
            "fare-policy",
            e.getEventId(),
            reviewRequired,
            enforcementEligible
        );
        segment = tripSegmentRepository.findById(segment.getId()).orElse(segment);

        Map<String, Object> farePayload = new LinkedHashMap<>();
        farePayload.put("segmentId", segment.getId());
        farePayload.put("originStation", segment.getOriginStation());
        farePayload.put("destinationStation", segment.getDestinationStation());
        farePayload.put("fareStatus", segment.getFareStatus().name());
        farePayload.put("policyStage", "FareValidation");
        farePayload.put("policyName", "FareCoveragePolicy");
        farePayload.put("decisionReason", summarizeFareDecision(segment));
        farePayload.put("matchedTripId", segment.getMatchedTripId());
        farePayload.put("confidenceScore", segment.getConfidenceScore());
        farePayload.put("confidenceThreshold", confidenceThreshold);
        farePayload.put("confidencePassed", confidencePassed);
        embedSegmentExplanation(farePayload, segment);
        farePayload.put("triggerEventIds", triggers);
        farePayload.put("reviewRequired", reviewRequired);
        farePayload.put("enforcementEligible", enforcementEligible);

        movementEventWriter.append(
            e.getUserId(),
            e.getCorrelationId() != null ? e.getCorrelationId() : UUID.randomUUID().toString(),
            MovementEventType.FareValidated,
            Instant.now(),
            farePayload,
            MovementSourceLayer.FARE_POLICY
        );
        metrics.recordFareValidated();
        metrics.recordPolicy(Duration.between(started, Instant.now()), "FareValidated");

        persistAudit(e.getUserId(), segment.getId(), "FareValidated", e.getCorrelationId(), farePayload);
        PlatformTraceLog.info(log, "fare-policy", "FareValidated", segment.getFareStatus().name(),
            e.getCorrelationId(), e.getEventId(), e.getUserId());
    }

    private void persistAudit(String userId, Long segmentId, String type, String correlationId, Map<String, Object> payload) {
        try {
            auditDecisionRecordRepository.save(AuditDecisionRecord.builder()
                .userId(userId)
                .segmentId(segmentId)
                .decisionType(type)
                .correlationId(correlationId != null ? correlationId : UUID.randomUUID().toString())
                .sourceLayer(MovementSourceLayer.FARE_POLICY.name())
                .payloadJson(objectMapper.writeValueAsString(payload))
                .recordedAt(Instant.now())
                .build());
        } catch (Exception ex) {
            log.warn("Failed to persist audit decision: {}", ex.getMessage());
        }
    }

    private static String value(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private static Instant instant(Object value, Instant fallback) {
        if (value == null) return fallback;
        if (value instanceof Instant i) return i;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException ex) {
            return fallback;
        }
    }

    private void embedSegmentExplanation(Map<String, Object> farePayload, TripSegment segment) {
        if (segment.getExplanationJson() == null || segment.getExplanationJson().isBlank()) return;
        try {
            Map<String, Object> full = objectMapper.readValue(segment.getExplanationJson(), new TypeReference<>() { });
            farePayload.put("segmentExplanation", full);
            Object fd = full.get("fareDecision");
            if (fd instanceof Map<?, ?> m && m.get("ruleCode") != null) {
                farePayload.put("fareRuleCode", String.valueOf(m.get("ruleCode")));
            }
            if (fd instanceof Map<?, ?> m2 && m2.get("detectionRule") != null) {
                farePayload.put("detectionRule", String.valueOf(m2.get("detectionRule")));
            }
        } catch (Exception ignored) {
            farePayload.put("segmentExplanationParseError", true);
        }
    }

    private static String summarizeFareDecision(TripSegment segment) {
        if (segment.getExplanationJson() == null || segment.getExplanationJson().isBlank()) {
            return "Fare outcome " + segment.getFareStatus().name() + " (no structured explanation persisted)";
        }
        return "See segmentExplanation.fareDecision; outcome=" + segment.getFareStatus().name();
    }
}
