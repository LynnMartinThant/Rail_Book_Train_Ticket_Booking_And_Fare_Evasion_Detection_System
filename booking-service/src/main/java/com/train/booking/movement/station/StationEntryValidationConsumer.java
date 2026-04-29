package com.train.booking.movement.station;

import com.train.booking.movement.eventlog.MovementEventEnvelope;
import com.train.booking.movement.eventlog.MovementEventRecordedEvent;
import com.train.booking.service.GeofenceRulesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Station/local layer: consumes structured movement fact {@code GeofenceEntered} and computes
 * station entry validation (ticket exists or not) with a traceable decision event.
 *
 * This replaces legacy {@code LocationEventPipelineConsumer} for station-entry validation in the event-driven flow.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StationEntryValidationConsumer {

    private final GeofenceRulesService geofenceRulesService;

    @EventListener
    @Transactional
    public void onMovementEvent(MovementEventRecordedEvent evt) {
        MovementEventEnvelope e = evt.getEnvelope();
        if (e == null || e.getUserId() == null || e.getEventType() == null) return;
        if (!"GeofenceEntered".equals(e.getEventType())) return;

        String stationName = null;
        Long geofenceId = null;
        if (e.getPayload() instanceof Map<?, ?> map) {
            Object s = map.get("stationName");
            stationName = s != null ? String.valueOf(s) : null;
            Object g = map.get("geofenceId");
            if (g instanceof Number n) geofenceId = n.longValue();
        }
        if (stationName == null || geofenceId == null) {
            log.debug("Skipping StationEntryValidation: missing stationName/geofenceId in payload");
            return;
        }
        geofenceRulesService.onStationEntry(e.getUserId(), stationName, geofenceId, e.getCorrelationId());
    }
}

