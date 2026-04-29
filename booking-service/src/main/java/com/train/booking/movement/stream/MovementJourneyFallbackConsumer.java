package com.train.booking.movement.stream;

import com.train.booking.movement.eventlog.MovementEventEnvelope;
import com.train.booking.movement.eventlog.MovementEventRecordedEvent;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.movement.snapshot.PassengerMovementSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Journey coordination (fallback): reconstructs segments from station-layer geofence facts when Kafka Streams is disabled.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "booking.movement.streams-enabled", havingValue = "false", matchIfMissing = true)
public class MovementJourneyFallbackConsumer {

    private final JourneySegmentCommandService journeySegmentCommandService;
    private final PassengerMovementSnapshotService snapshotService;
    private final MovementPipelineMetrics movementPipelineMetrics;
    private final Map<String, PassengerMovementState> states = new ConcurrentHashMap<>();

    @EventListener
    public void onMovementEvent(MovementEventRecordedEvent evt) {
        MovementEventEnvelope e = evt.getEnvelope();
        if (e == null || e.getUserId() == null || e.getEventType() == null) return;
        if (!"GeofenceEntered".equals(e.getEventType()) && !"GeofenceExited".equals(e.getEventType())) return;

        PassengerMovementState s = states.computeIfAbsent(e.getUserId(), k -> PassengerMovementState.builder().userId(e.getUserId()).build());
        if (e.getEventId() != null && e.getEventId().equals(s.getLastProcessedEventId())) {
            movementPipelineMetrics.recordDuplicateSuppressed("journey-coordination-fallback");
            return;
        }

        String station = stationFromPayload(e.getPayload());
        String platform = platformFromPayload(e.getPayload());
        Instant at = e.getOccurredAt() != null ? e.getOccurredAt() : Instant.now();
        s.setLastProcessedEventId(e.getEventId());
        s.setLastEventAt(at);
        s.setUserId(e.getUserId());
        s.setLastTransitionTime(at);
        s.setLastTransitionType("GeofenceEntered".equals(e.getEventType()) ? "ENTERED" : "EXITED");
        s.setLastGeofenceEventType("GeofenceEntered".equals(e.getEventType()) ? "ENTERED" : "EXITED");
        if (station != null) s.setCurrentStation(station);
        if (platform != null) s.setCurrentPlatform(platform);

        if ("GeofenceEntered".equals(e.getEventType())) {
            s.setJourneyStatus("AT_STATION");
        } else {
            s.setJourneyStatus("IN_TRANSIT");
        }

        if ("GeofenceExited".equals(e.getEventType()) && station != null) {
            s.setCandidateOriginStation(station);
            s.setCandidateOriginTime(at);
            snapshotService.maybeSnapshot(e.getUserId(), e.getEventId(), s);
            return;
        }

        if ("GeofenceEntered".equals(e.getEventType())
            && s.getCandidateOriginStation() != null
            && station != null
            && !s.getCandidateOriginStation().equalsIgnoreCase(station)) {
            String segmentKey = s.getCandidateOriginStation() + "|" + station + "|" + (s.getCandidateOriginTime() != null ? s.getCandidateOriginTime().getEpochSecond() : 0);
            if (!segmentKey.equals(s.getLastEmittedSegmentKey())) {
                s.setLastEmittedSegmentKey(segmentKey);
                journeySegmentCommandService.emitJourneySegmentConfirmed(
                    e.getUserId(),
                    e.getCorrelationId() != null ? e.getCorrelationId() : UUID.randomUUID().toString(),
                    s.getCandidateOriginStation(),
                    station,
                    s.getCandidateOriginTime(),
                    at
                );
                s.setJourneyStatus("SEGMENT_CONFIRMED");
            }
        }
        snapshotService.maybeSnapshot(e.getUserId(), e.getEventId(), s);
    }

    @SuppressWarnings("unchecked")
    private static String stationFromPayload(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) return null;
        Object station = map.get("stationName");
        return station != null ? String.valueOf(station) : null;
    }

    @SuppressWarnings("unchecked")
    private static String platformFromPayload(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) return null;
        Object platform = map.get("platform");
        return platform != null ? String.valueOf(platform) : null;
    }
}
