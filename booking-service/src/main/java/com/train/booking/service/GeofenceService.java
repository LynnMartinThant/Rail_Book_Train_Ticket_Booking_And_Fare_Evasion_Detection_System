package com.train.booking.service;

import com.train.booking.domain.AuditLog;
import com.train.booking.domain.Geofence;
import com.train.booking.domain.GeofenceEvent;
import com.train.booking.event.GeofenceEventMessage;
import com.train.booking.repository.GeofenceEventRepository;
import com.train.booking.repository.GeofenceRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeofenceService {

    private final GeofenceRepository geofenceRepository;
    private final GeofenceEventRepository geofenceEventRepository;
    private final TripRepository tripRepository;
    private final LocationEventStream locationEventStream;
    private final AuditLogService auditLogService;

    /**
     * Location Event Service: persist geofence event, then publish to event stream (Kafka or in-process).
     * Pipeline consumer runs: Drools (Warning) → Travel Detection → Violation Detection → Admin / Penalty.
     */
    @Transactional
    public GeofenceEvent recordEvent(String userId, Long geofenceId, GeofenceEvent.EventType eventType) {
        return recordEvent(userId, geofenceId, eventType, null);
    }

    /**
     * Same as above but with an optional timestamp for simulation (e.g. enter at 19:45, exit at 19:50).
     * When occurredAt is null, uses current time.
     */
    @Transactional
    public GeofenceEvent recordEvent(String userId, Long geofenceId, GeofenceEvent.EventType eventType, Instant occurredAt) {
        Geofence geofence = geofenceRepository.findById(geofenceId)
            .orElseThrow(() -> new IllegalArgumentException("Geofence not found: " + geofenceId));

        Instant at = occurredAt != null ? occurredAt : Instant.now();
        GeofenceEvent event = GeofenceEvent.builder()
            .userId(userId)
            .geofence(geofence)
            .eventType(eventType)
            .createdAt(at)
            .build();
        event = geofenceEventRepository.save(event);

        GeofenceEventMessage message = GeofenceEventMessage.builder()
            .userId(userId)
            .geofenceId(geofenceId)
            .eventType(eventType.name())
            .stationName(geofence.getStationName())
            .createdAt(event.getCreatedAt())
            .build();
        locationEventStream.publish(message);
        return event;
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
