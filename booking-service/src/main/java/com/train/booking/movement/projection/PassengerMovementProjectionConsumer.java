package com.train.booking.movement.projection;

import com.train.booking.movement.eventlog.MovementEventEnvelope;
import com.train.booking.movement.eventlog.MovementEventRecordedEvent;
import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.platform.MovementSourceLayer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-process read model for passenger movement (admin-supervision consumption).
 * Emits {@link MovementEventType#PassengerStateUpdated} for traceability (coordination layer).
 */
@Component
@RequiredArgsConstructor
public class PassengerMovementProjectionConsumer {

    private final PassengerMovementViewRepository repo;
    private final MovementPipelineMetrics metrics;
    private final MovementEventWriter movementEventWriter;

    @EventListener
    @Transactional
    public void onMovementEvent(MovementEventRecordedEvent evt) {
        MovementEventEnvelope e = evt.getEnvelope();
        if (e == null || e.getUserId() == null) return;

        if (!"GeofenceEntered".equals(e.getEventType()) && !"GeofenceExited".equals(e.getEventType())) {
            return;
        }
        if (e.getOccurredAt() != null) {
            metrics.recordProjectionDelay(Duration.between(e.getOccurredAt(), Instant.now()));
        }

        String station = null;
        String platform = null;
        if (e.getPayload() instanceof Map<?, ?> m) {
            Object v = m.get("stationName");
            station = v != null ? String.valueOf(v) : null;
            Object p = m.get("platform");
            platform = p != null ? String.valueOf(p) : null;
        }

        PassengerMovementView view = repo.findById(e.getUserId()).orElseGet(() ->
            PassengerMovementView.builder().userId(e.getUserId()).build()
        );
        view.setCurrentStation(station != null ? station : view.getCurrentStation());
        view.setCurrentPlatform(platform != null ? platform : view.getCurrentPlatform());
        view.setLastGeofenceEventType("GeofenceEntered".equals(e.getEventType()) ? "ENTERED" : "EXITED");
        view.setLastEventAt(e.getOccurredAt());
        view.setJourneyStatus("GeofenceEntered".equals(e.getEventType()) ? "AT_STATION" : "IN_TRANSIT");
        if ("GeofenceExited".equals(e.getEventType()) && station != null) {
            view.setCandidateOriginStation(station);
        }
        view.setUpdatedAt(Instant.now());
        repo.save(view);

        Map<String, Object> statePayload = new LinkedHashMap<>();
        statePayload.put("currentStation", view.getCurrentStation());
        statePayload.put("currentPlatform", view.getCurrentPlatform());
        statePayload.put("lastGeofenceEventType", view.getLastGeofenceEventType());
        statePayload.put("journeyStatus", view.getJourneyStatus());
        statePayload.put("candidateOriginStation", view.getCandidateOriginStation());
        movementEventWriter.append(
            e.getUserId(),
            e.getCorrelationId() != null ? e.getCorrelationId() : UUID.randomUUID().toString(),
            MovementEventType.PassengerStateUpdated,
            e.getOccurredAt() != null ? e.getOccurredAt() : Instant.now(),
            statePayload,
            MovementSourceLayer.JOURNEY_COORDINATION
        );
    }
}
