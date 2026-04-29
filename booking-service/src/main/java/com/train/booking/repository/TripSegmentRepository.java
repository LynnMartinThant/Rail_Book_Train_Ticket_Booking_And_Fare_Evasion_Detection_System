package com.train.booking.repository;

import com.train.booking.domain.FareStatus;
import com.train.booking.domain.TripSegment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TripSegmentRepository extends JpaRepository<TripSegment, Long> {

    List<TripSegment> findByReservationId(Long reservationId);

    @Query("select s from TripSegment s where s.reservationId is not null")
    List<TripSegment> findAllWithReservationId();

    Optional<TripSegment> findByIdempotencyKey(String idempotencyKey);

    List<TripSegment> findByPassengerIdOrderByCreatedAtDesc(String passengerId, Pageable pageable);
    List<TripSegment> findByPassengerIdIn(List<String> passengerIds);

    List<TripSegment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<TripSegment> findByFareStatusAndResolutionDeadlineBefore(FareStatus fareStatus, Instant before);

    List<TripSegment> findByFareStatusInOrderByCreatedAtDesc(List<FareStatus> statuses, Pageable pageable);

    long countByFareStatus(FareStatus fareStatus);

    void deleteByPassengerIdIn(List<String> passengerIds);
}
