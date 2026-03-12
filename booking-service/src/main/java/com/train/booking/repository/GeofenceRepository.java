package com.train.booking.repository;

import com.train.booking.domain.Geofence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeofenceRepository extends JpaRepository<Geofence, Long> {

    List<Geofence> findAllByOrderByNameAsc();

    Optional<Geofence> findByName(String name);

    Optional<Geofence> findByStationName(String stationName);
}
