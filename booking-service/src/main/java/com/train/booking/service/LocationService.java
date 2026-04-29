package com.train.booking.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.train.booking.domain.Geofence;
import com.train.booking.domain.UserLocation;
import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.platform.MovementSourceLayer;
import com.train.booking.quality.DataQualityAssessment;
import com.train.booking.quality.DataQualityScoringService;
import com.train.booking.repository.UserLocationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**

 * stores latest position. Ingestion is intentionally minimal: the service records a pseudonymous
 * user identifier, a timestamp/correlation context, and raw location/beacon inputs as transient
 * signals rather than preserving a continuous movement history.
 * Geofence enter/exit detection is delegated to {@link GeofenceService}
 * (station / local layer).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {

    private final UserLocationRepository userLocationRepository;
    private final GeofenceService geofenceService;
    private final MovementEventWriter movementEventWriter;
    private final MovementPipelineMetrics movementPipelineMetrics;
    private final DataQualityScoringService dataQualityScoringService;

    /**
     * Report user's current location. Updates stored position; station layer evaluates geofence transitions.
     * This endpoint accepts a pseudonymous user id and raw location input as a transient signal;
     * Optionally pass accuracyMeters (GPS) for journey reconstruction confidence scoring.
     */
    @Transactional
    public UserLocation reportLocation(String userId, double latitude, double longitude) {
        return reportLocation(userId, latitude, longitude, null);
    }

    @Transactional
    public UserLocation reportLocation(String userId, double latitude, double longitude, Double accuracyMeters) {
        String correlationId = UUID.randomUUID().toString();
        Map<String, Object> locationPayload = new LinkedHashMap<>();
        locationPayload.put("latitude", latitude);
        locationPayload.put("longitude", longitude);
        locationPayload.put("accuracyMeters", accuracyMeters);
        movementEventWriter.append(
            userId,
            correlationId,
            MovementEventType.LocationReported, /*Converts raw input into a structured event*/
            null,
            locationPayload,
            MovementSourceLayer.MOVEMENT_INGESTION
        );
        UserLocation previous = userLocationRepository.findByUserId(userId).orElse(null);
        Duration lag = previous != null && previous.getUpdatedAt() != null
            ? Duration.between(previous.getUpdatedAt(), Instant.now())
            : Duration.ZERO;
        double jumpMetres = previous != null
            ? distanceMetres(previous.getLatitude(), previous.getLongitude(), latitude, longitude)
            : 0.0;
        boolean duplicate = previous != null
            && Double.compare(previous.getLatitude(), latitude) == 0
            && Double.compare(previous.getLongitude(), longitude) == 0;
        DataQualityAssessment quality = dataQualityScoringService.assess(accuracyMeters, lag, jumpMetres, duplicate); /*checks whether the data is reliable before using it*/
        Map<String, Object> qualityPayload = new LinkedHashMap<>();
        qualityPayload.put("trustScore", quality.trustScore());
        qualityPayload.put("usableForInference", quality.usableForInference());
        qualityPayload.put("usableForEnforcement", quality.usableForEnforcement());
        qualityPayload.put("issues", quality.issues());
        qualityPayload.put("jumpMetres", jumpMetres);
        movementEventWriter.append(
            userId,
            correlationId,
            MovementEventType.DataQualityAssessed,
            null,
            qualityPayload,
            MovementSourceLayer.MOVEMENT_INGESTION
        );
        movementPipelineMetrics.recordLocationAccepted();

        UserLocation current = previous != null
            ? previous
            : UserLocation.builder().userId(userId).latitude(latitude).longitude(longitude).updatedAt(null).build();
        current.setLatitude(latitude);
        current.setLongitude(longitude);
        current = userLocationRepository.save(current);

        if (quality.usableForInference()) {
            geofenceService.applyLocationReportForStationTransitions(userId, previous, latitude, longitude, accuracyMeters, correlationId);
        } else {
            Map<String, Object> rejected = new LinkedHashMap<>();
            rejected.put("reason", "Data quality too weak for station inference");
            rejected.put("trustScore", quality.trustScore());
            rejected.put("issues", quality.issues());
            movementEventWriter.append(
                userId,
                correlationId,
                MovementEventType.LocationRejected,
                null,
                rejected,
                MovementSourceLayer.MOVEMENT_INGESTION
            );
        }
        return current;
    }

    public List<UserLocation> getAllUserLocations() {
        return userLocationRepository.findAllByOrderByUpdatedAtDesc();
    }

    /** Save or update user location without running geofence detection . */
    @Transactional
    public UserLocation saveUserLocationOnly(String userId, double latitude, double longitude) {
        UserLocation loc = userLocationRepository.findByUserId(userId)
            .orElse(UserLocation.builder().userId(userId).latitude(latitude).longitude(longitude).updatedAt(null).build());
        loc.setLatitude(latitude);
        loc.setLongitude(longitude);
        return userLocationRepository.save(loc);
    }

    /**
     * Resolve current station from last reported coordinates via station-layer geofence registry (not raw GPS in admin APIs).
     */
    public java.util.Optional<Geofence> getCurrentStation(String userId) {
        return userLocationRepository.findByUserId(userId)
            .flatMap(loc -> geofenceService.resolveStationForCoordinates(loc.getLatitude(), loc.getLongitude()));
    }

    private static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusMetres = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMetres * c;
    }
}
