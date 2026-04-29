package com.train.booking.event;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.train.booking.service.GeofenceRulesService;
import com.train.booking.service.JourneyDroolsPipelineService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumes geofence events from the in-process event stream.
 * Pipeline: Location Events → Drools (Data Validation → Journey Reconstruction → Ticket Verification → Fraud Detection → Risk Scoring) → Fraud Alert / Case Creation.
 * Also runs the legacy warning rule (no ticket at entry → Buy / Ignore / Scan QR).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocationEventPipelineConsumer {

    private final GeofenceRulesService geofenceRulesService;
    private final JourneyDroolsPipelineService journeyDroolsPipelineService;
    @Value("${booking.movement.event-driven-pipeline-enabled:false}")
    private boolean eventDrivenPipelineEnabled;

    @EventListener
    public void onGeofenceEventRecorded(GeofenceEventRecordedEvent event) {
        handle(event.getUserId(), event.getGeofenceId(), event.getEventType(), event.getStationName(), event.getCreatedAt(), event.getAccuracyMeters(), event.getCorrelationId());
    }

    /** Entry point for Kafka consumer (see KafkaLocationEventConsumer). */
    public void handle(String userId, Long geofenceId, String eventType, String stationName, Instant createdAt, Double accuracyMeters, String correlationId) {
        if (!"ENTERED".equalsIgnoreCase(eventType)) {
            return;
        }
        log.debug("Pipeline processing ENTERED: userId={} station={}", userId, stationName);
        // Legacy warning: Drools no-ticket-at-entry → options (Buy / Ignore / Scan QR)
        geofenceRulesService.onStationEntry(userId, stationName, geofenceId, correlationId);
        if (eventDrivenPipelineEnabled) {
            // Dedupe gate: journey/fare/fraud are handled by movement event-driven pipeline.
            log.debug("Skipping legacy journey pipeline for user {} (event-driven pipeline enabled)", userId);
            return;
        }
        // Layered Drools pipeline: validate events, reconstruct journeys, verify tickets, fraud detection, risk scoring → segments + alerts + cases
        JourneyDroolsPipelineService.PipelineResult result = journeyDroolsPipelineService.runPipelineForUser(userId);
        if (result.getJourneysCreated() > 0 || result.getFraudAlerts() > 0 || result.getInvestigationCases() > 0) {
            log.debug("Drools pipeline result: journeys={} alerts={} cases={}", result.getJourneysCreated(), result.getFraudAlerts(), result.getInvestigationCases());
        }
    }
}
