package com.train.booking.platform.fraudpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.train.booking.domain.AuditDecisionRecord;
import com.train.booking.domain.FareStatus;
import com.train.booking.movement.eventlog.MovementEventEnvelope;
import com.train.booking.movement.eventlog.MovementEventRecordedEvent;
import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.platform.MovementSourceLayer;
import com.train.booking.platform.observability.PlatformTraceLog;
import com.train.booking.repository.AuditDecisionRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fraud / escalation policy: consumes fare outcomes and booking/movement facts via {@link MovementEventType#FareValidated}.
 * Does not mutate booking truth; emits {@link MovementEventType#FraudDecisionMade} for review workflows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FraudPolicyConsumer {

    private final MovementEventWriter movementEventWriter;
    private final MovementPipelineMetrics metrics;
    private final ObjectMapper objectMapper;
    private final AuditDecisionRecordRepository auditDecisionRecordRepository;

    @EventListener
    @Transactional
    public void onMovementEvent(MovementEventRecordedEvent evt) {
        MovementEventEnvelope e = evt.getEnvelope();
        if (e == null || e.getUserId() == null || e.getEventType() == null) return;
        if (!"FareValidated".equals(e.getEventType())) return;

        handleFareValidated(e);
    }

    private void handleFareValidated(MovementEventEnvelope e) {
        Instant started = Instant.now();
        if (!(e.getPayload() instanceof Map<?, ?> payload)) return;
        String fareStatus = value(payload, "fareStatus");
        if (fareStatus == null) return;
        FareStatus status;
        try {
            status = FareStatus.valueOf(fareStatus);
        } catch (IllegalArgumentException ex) {
            return;
        }
        boolean reviewRequired = Boolean.parseBoolean(value(payload, "reviewRequired"));
        if (status == FareStatus.PAID && !reviewRequired) return;

        Map<String, Object> fraudPayload = new LinkedHashMap<>();
        fraudPayload.put("policyStage", "FraudEscalation");
        fraudPayload.put("policyName", "ConfidenceThresholdPolicy");
        fraudPayload.put("ruleName", reviewRequired && status == FareStatus.PAID ? "LOW_CONFIDENCE_REVIEW" : fraudRuleForFareStatus(status));
        fraudPayload.put("decisionReason", reviewRequired && status == FareStatus.PAID
            ? "Escalated due to low confidence in journey reconstruction; manual review required."
            : fraudReasonForFareStatus(status, payload));
        fraudPayload.put("fareStatus", status.name());
        fraudPayload.put("decisionAction", "REVIEW_REQUIRED");
        fraudPayload.put("segmentId", payload.get("segmentId"));
        fraudPayload.put("confidenceScore", payload.get("confidenceScore"));
        fraudPayload.put("confidenceThreshold", payload.get("confidenceThreshold"));
        fraudPayload.put("confidencePassed", payload.get("confidencePassed"));
        fraudPayload.put("priorDecisionReason", payload.get("decisionReason"));
        fraudPayload.put("triggerEventIds", java.util.List.of(e.getEventId()));

        movementEventWriter.append(
            e.getUserId(),
            e.getCorrelationId() != null ? e.getCorrelationId() : UUID.randomUUID().toString(),
            MovementEventType.FraudDecisionMade,
            Instant.now(),
            fraudPayload,
            MovementSourceLayer.FRAUD_POLICY
        );
        metrics.recordFraudDecision();
        metrics.recordPolicy(Duration.between(started, Instant.now()), "FraudDecisionMade");

        Object segId = payload.get("segmentId");
        Long segmentId = segId instanceof Number n ? n.longValue() : null;
        persistAudit(e.getUserId(), segmentId, "FraudDecisionMade", e.getCorrelationId(), fraudPayload);
        PlatformTraceLog.warn(log, "fraud-policy", "FraudDecisionMade", "REVIEW_REQUIRED",
            e.getCorrelationId(), e.getEventId(), e.getUserId(), status.name());
    }

    private void persistAudit(String userId, Long segmentId, String type, String correlationId, Map<String, Object> payload) {
        try {
            auditDecisionRecordRepository.save(AuditDecisionRecord.builder()
                .userId(userId)
                .segmentId(segmentId)
                .decisionType(type)
                .correlationId(correlationId != null ? correlationId : UUID.randomUUID().toString())
                .sourceLayer(MovementSourceLayer.FRAUD_POLICY.name())
                .payloadJson(objectMapper.writeValueAsString(payload))
                .recordedAt(Instant.now())
                .build());
        } catch (Exception ex) {
            log.warn("Failed to persist fraud audit: {}", ex.getMessage());
        }
    }

    private static String value(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private static String fraudRuleForFareStatus(FareStatus status) {
        return switch (status) {
            case UNDERPAID -> "OVER_TRAVEL_REVIEW";
            case PENDING_RESOLUTION -> "NO_TICKET_OR_ROUTE_REVIEW";
            case UNPAID_TRAVEL -> "UNPAID_TRAVEL_REVIEW";
            default -> "FARE_ANOMALY_REVIEW";
        };
    }

    private static String fraudReasonForFareStatus(FareStatus status, Map<?, ?> fareValidatedPayload) {
        String base = "Escalated after fare validation: " + status.name() + ".";
        return switch (status) {
            case UNDERPAID -> base + " Passenger travelled beyond paid ticket destination; additional fare may apply.";
            case PENDING_RESOLUTION -> base + " No covering ticket or route violation pending resolution.";
            case UNPAID_TRAVEL -> base + " Penalty or unpaid travel state recorded.";
            default -> base;
        };
    }
}
