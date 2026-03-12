package com.train.booking.service;

import com.train.booking.domain.Geofence;
import com.train.booking.domain.GeofenceEvent;
import com.train.booking.domain.UserLocation;
import com.train.booking.repository.GeofenceRepository;
import com.train.booking.repository.UserLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Processes user location updates: persists latest position and detects geofence entry/exit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {

    private static final double EARTH_RADIUS_METRES = 6_371_000;

    private final UserLocationRepository userLocationRepository;
    private final GeofenceRepository geofenceRepository;
    private final GeofenceService geofenceService;

    /**
     * Report user's current location. Updates stored position and detects ENTERED/EXITED for each geofence.
     */
    @Transactional
    public UserLocation reportLocation(String userId, double latitude, double longitude) {
        UserLocation previous = userLocationRepository.findByUserId(userId).orElse(null);

        UserLocation current = previous != null
            ? previous
            : UserLocation.builder().userId(userId).latitude(latitude).longitude(longitude).updatedAt(null).build();
        current.setLatitude(latitude);
        current.setLongitude(longitude);
        current = userLocationRepository.save(current);

        List<Geofence> geofences = geofenceRepository.findAllByOrderByNameAsc();
        for (Geofence g : geofences) {
            boolean nowInside = isInside(latitude, longitude, g.getLatitude(), g.getLongitude(), g.getRadiusMeters());
            boolean wasInside = previous != null && isInside(
                previous.getLatitude(), previous.getLongitude(),
                g.getLatitude(), g.getLongitude(), g.getRadiusMeters());

            if (!wasInside && nowInside) {
                geofenceService.recordEvent(userId, g.getId(), GeofenceEvent.EventType.ENTERED);
            } else if (wasInside && !nowInside) {
                geofenceService.recordEvent(userId, g.getId(), GeofenceEvent.EventType.EXITED);
            }
        }
        return current;
    }

    public List<UserLocation> getAllUserLocations() {
        return userLocationRepository.findAllByOrderByUpdatedAtDesc();
    }

    /** Save or update user location without running geofence detection (e.g. for demo data). */
    @Transactional
    public UserLocation saveUserLocationOnly(String userId, double latitude, double longitude) {
        UserLocation loc = userLocationRepository.findByUserId(userId)
            .orElse(UserLocation.builder().userId(userId).latitude(latitude).longitude(longitude).updatedAt(null).build());
        loc.setLatitude(latitude);
        loc.setLongitude(longitude);
        return userLocationRepository.save(loc);
    }

    /**
     * Resolve current station for the user from their last reported location.
     * Returns the geofence (station) that contains the user, or the nearest geofence if none contains them.
     */
    public java.util.Optional<Geofence> getCurrentStation(String userId) {
        return userLocationRepository.findByUserId(userId)
            .flatMap(loc -> {
                List<Geofence> geofences = geofenceRepository.findAllByOrderByNameAsc();
                for (Geofence g : geofences) {
                    if (isInside(loc.getLatitude(), loc.getLongitude(), g.getLatitude(), g.getLongitude(), g.getRadiusMeters())) {
                        return java.util.Optional.of(g);
                    }
                }
                if (geofences.isEmpty()) return java.util.Optional.empty();
                Geofence nearest = geofences.get(0);
                double minDist = distanceMetres(loc.getLatitude(), loc.getLongitude(), nearest.getLatitude(), nearest.getLongitude());
                for (int i = 1; i < geofences.size(); i++) {
                    Geofence g = geofences.get(i);
                    double d = distanceMetres(loc.getLatitude(), loc.getLongitude(), g.getLatitude(), g.getLongitude());
                    if (d < minDist) { minDist = d; nearest = g; }
                }
                return java.util.Optional.of(nearest);
            });
    }

    private static boolean isInside(double userLat, double userLon, double centerLat, double centerLon, int radiusMeters) {
        return distanceMetres(userLat, userLon, centerLat, centerLon) <= radiusMeters;
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
}
