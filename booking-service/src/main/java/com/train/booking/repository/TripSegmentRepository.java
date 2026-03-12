package com.train.booking.repository;

import com.train.booking.domain.FareStatus;
import com.train.booking.domain.TripSegment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TripSegmentRepository extends JpaRepository<TripSegment, Long> {

    Optional<TripSegment> findByIdempotencyKey(String idempotencyKey);

    List<TripSegment> findByPassengerIdOrderByCreatedAtDesc(String passengerId, Pageable pageable);

    List<TripSegment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<TripSegment> findByFareStatusAndResolutionDeadlineBefore(FareStatus fareStatus, Instant before);

    List<TripSegment> findByFareStatusInOrderByCreatedAtDesc(List<FareStatus> statuses, Pageable pageable);
}
