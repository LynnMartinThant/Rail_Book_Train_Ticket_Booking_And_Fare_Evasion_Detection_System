package com.train.booking.repository;

import com.train.booking.domain.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    @Query("select t from Trip t join fetch t.train order by t.departureTime")
    List<Trip> findAllWithTrain();

    @Query("select t from Trip t join fetch t.train where t.id = :id")
    Optional<Trip> findByIdWithTrain(@Param("id") Long id);

    @Query("select t from Trip t join fetch t.train where t.fromStation = :fromStation and t.toStation = :toStation order by t.departureTime")
    List<Trip> findByFromStationAndToStation(@Param("fromStation") String fromStation, @Param("toStation") String toStation);

    List<Trip> findTop1ByFromStationOrderByDepartureTimeAsc(String fromStation);

    /** Departures from a station (for dashboard). Future departures only, ordered by time. */
    @Query("select t from Trip t join fetch t.train where t.fromStation = :stationName and t.departureTime >= :after order by t.departureTime")
    List<Trip> findByFromStationAndDepartureTimeAfterOrderByDepartureTimeAsc(
        @Param("stationName") String stationName,
        @Param("after") Instant after);

    /** Train matching: trips from origin to destination with departure within time window (e.g. ±5 min of segment start). */
    @Query("select t from Trip t join fetch t.train where t.fromStation = :fromStation and t.toStation = :toStation and t.departureTime between :windowStart and :windowEnd order by t.departureTime")
    List<Trip> findByFromStationAndToStationAndDepartureTimeBetween(
        @Param("fromStation") String fromStation,
        @Param("toStation") String toStation,
        @Param("windowStart") Instant windowStart,
        @Param("windowEnd") Instant windowEnd);
}
