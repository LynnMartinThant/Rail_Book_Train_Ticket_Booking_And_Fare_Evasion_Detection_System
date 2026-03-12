package com.train.booking.repository;

import com.train.booking.domain.TripSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface TripSeatRepository extends JpaRepository<TripSeat, Long> {

    @Query("select ts from TripSeat ts join fetch ts.seat join fetch ts.trip where ts.trip.id = :tripId order by ts.seat.seatNumber")
    List<TripSeat> findByTripId(Long tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ts from TripSeat ts join fetch ts.seat join fetch ts.trip where ts.trip.id = :tripId and ts.seat.id = :seatId")
    Optional<TripSeat> findByTripIdAndSeatIdForUpdate(Long tripId, Long seatId);

    int countByTripId(Long tripId);
}
