package com.train.booking.repository;

import com.train.booking.domain.GeofenceEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GeofenceEventRepository extends JpaRepository<GeofenceEvent, Long> { // geofence event repo for  journey reconstruction

    List<GeofenceEvent> findAllByOrderByCreatedAtDesc(org.springframework.data.domain.Pageable pageable); // all event rec , event time, entry or exit when user comes to geofence

    @Query("select e from GeofenceEvent e join fetch e.geofence where e.userId = :userId order by e.createdAt desc")
    List<GeofenceEvent> findByUserIdOrderByCreatedAtDesc(@Param("userId") String userId, org.springframework.data.domain.Pageable pageable);

    @Query("select e from GeofenceEvent e join fetch e.geofence where e.userId = :userId and e.eventType = 'ENTERED' order by e.createdAt desc")
    List<GeofenceEvent> findEnteredEventsByUserIdOrderByCreatedAtDesc(@Param("userId") String userId, org.springframework.data.domain.Pageable pageable);

    @Query("select e from GeofenceEvent e join fetch e.geofence where e.userId = :userId and e.eventType = 'EXITED' order by e.createdAt desc")
    List<GeofenceEvent> findExitedEventsByUserIdOrderByCreatedAtDesc(@Param("userId") String userId, org.springframework.data.domain.Pageable pageable);

    void deleteByUserIdIn(List<String> userIds);
}
