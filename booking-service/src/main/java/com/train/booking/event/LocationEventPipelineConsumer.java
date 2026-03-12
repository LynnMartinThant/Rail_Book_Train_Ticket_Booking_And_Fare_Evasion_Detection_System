package com.train.booking.event;

import com.train.booking.service.GeofenceRulesService;
import com.train.booking.service.TripSegmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Consumes geofence events from the in-process event stream.
 * Pipeline: Warning System (Drools) → Travel Detection → Violation Detection.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocationEventPipelineConsumer {

    private final GeofenceRulesService geofenceRulesService;
    private final TripSegmentService tripSegmentService;

    @EventListener
    public void onGeofenceEventRecorded(GeofenceEventRecordedEvent event) {
        handle(event.getUserId(), event.getGeofenceId(), event.getEventType(), event.getStationName(), event.getCreatedAt());
    }

    /** Entry point for Kafka consumer (see KafkaLocationEventConsumer). */
    public void handle(String userId, Long geofenceId, String eventType, String stationName, Instant createdAt) {
        if (!"ENTERED".equalsIgnoreCase(eventType)) {
            return;
        }
        log.debug("Pipeline processing ENTERED: userId={} station={}", userId, stationName);
        // Warning System: Drools – no ticket at entry → options (Buy / Ignore / Scan QR)
        geofenceRulesService.onStationEntry(userId, stationName, geofenceId);
        // Travel Detection + Violation Detection: trip segment, fare status (PAID/UNDERPAID/PENDING_RESOLUTION/UNPAID_TRAVEL)
        tripSegmentService.onStationEntryDetected(userId, stationName, createdAt, geofenceId);
    }
}
