package com.train.booking.service;

import com.train.booking.domain.AuditLog;
import com.train.booking.domain.Geofence;
import com.train.booking.domain.GeofenceEvent;
import com.train.booking.domain.UserLocation;
import com.train.booking.event.GeofenceEventMessage;
import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.platform.MovementSourceLayer;
import com.train.booking.repository.GeofenceEventRepository;
import com.train.booking.repository.GeofenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeofenceService {

    private static final double EARTH_RADIUS_METRES = 6_371_000;

    private final GeofenceRepository geofenceRepository;
    private final GeofenceEventRepository geofenceEventRepository;
    private final LocationEventStream locationEventStream;
    private final AuditLogService auditLogService;
    private final MovementEventWriter movementEventWriter;
    private final MovementPipelineMetrics movementPipelineMetrics;

    @Value("${booking.movement.event-driven-pipeline-enabled:true}")
    private boolean movementEventDrivenEnabled;

    /**
     * Location Event Service: persist geofence event, then publish to event stream (Kafka or in-process).
     * Pipeline consumer runs: Drools (Warning) → Travel Detection → Violation Detection → Admin / Penalty.
     */
    @Transactional
    public GeofenceEvent recordEvent(String userId, Long geofenceId, GeofenceEvent.EventType eventType) {
        return recordEvent(userId, geofenceId, eventType, null, null);
    }

    @Transactional
    public GeofenceEvent recordEvent(String userId, Long geofenceId, GeofenceEvent.EventType eventType, Instant occurredAt) {
        return recordEvent(userId, geofenceId, eventType, occurredAt, null);
    }

    /**
     * Persist geofence event and publish to stream. accuracyMeters (GPS) used for journey reconstruction confidence.
     */
    @Transactional
    public GeofenceEvent recordEvent(String userId, Long geofenceId, GeofenceEvent.EventType eventType, Instant occurredAt, Double accuracyMeters) {
        return recordEvent(userId, geofenceId, eventType, occurredAt, accuracyMeters, null);
    }

    /**
     * Persist geofence event, publish to legacy location stream, and append station-layer movement fact
     * ({@link MovementEventType#GeofenceEntered} / {@link MovementEventType#GeofenceExited}).
     *
     * @param movementCorrelationId correlation to link with a prior {@link MovementEventType#LocationReported}; if null, a new id is used
     */
    @Transactional
    public GeofenceEvent recordEvent(String userId, Long geofenceId, GeofenceEvent.EventType eventType, Instant occurredAt, Double accuracyMeters, String movementCorrelationId) {
        Geofence geofence = geofenceRepository.findById(geofenceId)
            .orElseThrow(() -> new IllegalArgumentException("Geofence not found: " + geofenceId));

        Instant at = occurredAt != null ? occurredAt : Instant.now();
        String corr = movementCorrelationId != null && !movementCorrelationId.isBlank()
            ? movementCorrelationId
            : UUID.randomUUID().toString();

        GeofenceEvent event = GeofenceEvent.builder()
            .userId(userId)
            .geofence(geofence)
            .eventType(eventType)
            .createdAt(at)
            .accuracyMeters(accuracyMeters)
            .build();
        event = geofenceEventRepository.save(event);

        GeofenceEventMessage message = GeofenceEventMessage.builder()
            .userId(userId)
            .geofenceId(geofenceId)
            .eventType(eventType.name())
            .stationName(geofence.getStationName())
            .createdAt(event.getCreatedAt())
            .accuracyMeters(accuracyMeters)
            .correlationId(corr)
            .build();
   
        if (!movementEventDrivenEnabled) {
            locationEventStream.publish(message);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("geofenceId", geofenceId);
        payload.put("stationId", geofence.getId());
        payload.put("stationName", geofence.getStationName());
        payload.put("platform", geofence.getPlatform());
        payload.put("accuracyMeters", accuracyMeters);
        MovementEventType mt = eventType == GeofenceEvent.EventType.ENTERED
            ? MovementEventType.GeofenceEntered
            : MovementEventType.GeofenceExited;
        movementEventWriter.append(userId, corr, mt, at, payload, MovementSourceLayer.STATION_PROCESSING);
        movementPipelineMetrics.recordGeofenceTransition();
        return event;
    }

    /**
     * Station / local layer: point-in-zone vs registry and ENTER/EXIT transitions (circle geofence).
     * Ingestion persists coordinates elsewhere; this method only decides station membership and emits {@link #recordEvent} facts.
     */
    @Transactional
    public void applyLocationReportForStationTransitions(
        String userId,
        UserLocation previous,
        double latitude,
        double longitude,
        Double accuracyMeters,
        String correlationId
    ) {
        List<Geofence> geofences = geofenceRepository.findAllByOrderByNameAsc();
        for (Geofence g : geofences) {
            boolean nowInside = pointInsideGeofence(g, latitude, longitude);
            boolean wasInside = previous != null && pointInsideGeofence(g, previous.getLatitude(), previous.getLongitude());
            if (!wasInside && nowInside) {
                recordEvent(userId, g.getId(), GeofenceEvent.EventType.ENTERED, null, accuracyMeters, correlationId);
            } else if (wasInside && !nowInside) {
                recordEvent(userId, g.getId(), GeofenceEvent.EventType.EXITED, null, accuracyMeters, correlationId);
            }
        }
    }

    /** Current station: geofence containing the point, else nearest station geofence. */
    public Optional<Geofence> resolveStationForCoordinates(double latitude, double longitude) {
        List<Geofence> geofences = geofenceRepository.findAllByOrderByNameAsc();
        if (geofences.isEmpty()) {
            return Optional.empty();
        }
        for (Geofence g : geofences) {
            if (pointInsideGeofence(g, latitude, longitude)) {
                return Optional.of(g);
            }
        }
        Geofence nearest = geofences.get(0);
        double minDist = distanceMetres(latitude, longitude, nearest.getLatitude(), nearest.getLongitude());
        for (int i = 1; i < geofences.size(); i++) {
            Geofence g = geofences.get(i);
            double d = distanceMetres(latitude, longitude, g.getLatitude(), g.getLongitude());
            if (d < minDist) {
                minDist = d;
                nearest = g;
            }
        }
        return Optional.of(nearest);
    }

    private static boolean pointInsideGeofence(Geofence g, double lat, double lon) {
        return distanceMetres(lat, lon, g.getLatitude(), g.getLongitude()) <= g.getRadiusMeters();
    }

    private static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METRES * c;
    }

    public List<Geofence> listGeofences() {
        return geofenceRepository.findAllByOrderByNameAsc();
    }

    public Optional<Geofence> getGeofence(Long id) {
        return geofenceRepository.findById(id);
    }

    /** Same as getGeofence but throws if not found (for admin simulation). */
    public Geofence getGeofenceById(Long id) {
        return geofenceRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Geofence not found: " + id));
    }

    public Geofence createGeofence(String name, String stationName, double latitude, double longitude, int radiusMeters) {
        Geofence g = Geofence.builder()
            .name(name)
            .stationName(stationName)
            .latitude(latitude)
            .longitude(longitude)
            .radiusMeters(radiusMeters)
            .build();
        return geofenceRepository.save(g);
    }

    public List<GeofenceEvent> listRecentEvents(int limit) {
        int max = Math.min(Math.max(1, limit), 200);
        return geofenceEventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, max));
    }

    public List<AuditLog> listFareEvasionAudit(int limit) {
        int max = Math.min(Math.max(1, limit), 200);
        return auditLogService.findByAction("FARE_EVASION", max);
    }

    public Optional<Geofence> findGeofenceByName(String name) {
        return geofenceRepository.findByName(name);
    }
}
