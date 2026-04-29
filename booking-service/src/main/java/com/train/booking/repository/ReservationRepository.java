package com.train.booking.repository;

import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Query("select r from Reservation r join fetch r.tripSeat ts join fetch ts.seat join fetch ts.trip t join fetch t.train where r.userId = :userId order by r.createdAt desc")
    List<Reservation> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("select r from Reservation r join fetch r.tripSeat ts join fetch ts.seat join fetch ts.trip t join fetch t.train order by r.createdAt desc")
    List<Reservation> findAllWithDetailsOrderByCreatedAtDesc();

    List<Reservation> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<ReservationStatus> statuses);

    Optional<Reservation> findByIdAndUserId(Long id, String userId);

    @Query("select r from Reservation r join fetch r.tripSeat ts join fetch ts.seat join fetch ts.trip where r.id = :id and r.userId = :userId")
    Optional<Reservation> findByIdAndUserIdWithDetails(Long id, String userId);

    /** Same as above but also fetches train (for PDF ticket). */
    @Query("select r from Reservation r join fetch r.tripSeat ts join fetch ts.seat join fetch ts.trip t join fetch t.train where r.id = :id and r.userId = :userId")
    Optional<Reservation> findByIdAndUserIdWithFullDetails(Long id, String userId);

    @Query("select r from Reservation r where r.tripSeat.id = :tripSeatId and r.status in :statuses and (r.expiresAt is null or r.expiresAt > :now)")
    Optional<Reservation> findActiveByTripSeatId(Long tripSeatId, List<ReservationStatus> statuses, Instant now);

    /** Latest reservation for this trip seat (any status). Used to reuse row for rebooking. */
    @Query("select r from Reservation r join fetch r.tripSeat ts join fetch ts.seat join fetch ts.trip where r.tripSeat.id = :tripSeatId order by r.id desc")
    List<Reservation> findByTripSeatIdOrderByIdDesc(Long tripSeatId, org.springframework.data.domain.Pageable pageable);

    @Query("select count(r) > 0 from Reservation r join r.tripSeat ts join ts.trip t where r.userId = :userId and r.status = :status and t.fromStation = :fromStation and t.toStation = :toStation")
    boolean existsByUserIdAndStatusAndTripRoute(@Param("userId") String userId, @Param("status") ReservationStatus status, @Param("fromStation") String fromStation, @Param("toStation") String toStation);

    /** True if user has any CONFIRMED reservation for a trip starting from this station (any destination). */
    @Query("select count(r) > 0 from Reservation r join r.tripSeat ts join ts.trip t where r.userId = :userId and r.status = :status and t.fromStation = :fromStation")
    boolean existsByUserIdAndStatusAndTripFromStation(@Param("userId") String userId, @Param("status") ReservationStatus status, @Param("fromStation") String fromStation);

    /** True if user has a CONFIRMED reservation with this journey origin (segment ticket, e.g. Meadowhall → Sheffield). */
    @Query("select count(r) > 0 from Reservation r where r.userId = :userId and r.status = :status and r.journeyFromStation is not null and lower(r.journeyFromStation) = lower(:stationName)")
    boolean existsByUserIdAndStatusAndJourneyFromStation(@Param("userId") String userId, @Param("status") ReservationStatus status, @Param("stationName") String stationName);

    /** Lock reservation for atomic QR validation; prevents double-use. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id and r.userId = :userId")
    Optional<Reservation> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") String userId);

    /** Bulk delete all reservations (for reset-on-startup). Ensures DELETEs run before trip_seat deletes. */
    @Modifying
    @Query("delete from Reservation r")
    void deleteAllReservations();

    /** For webhook: find reservation by gateway payment intent/transaction ID (idempotent handling). */
    Optional<Reservation> findByPaymentTransactionId(String paymentTransactionId);

    /** Count reservations for a trip in given statuses (for pricing: seats sold). */
    @Query("select count(r) from Reservation r join r.tripSeat ts where ts.trip.id = :tripId and r.status in :statuses")
    long countByTripIdAndStatusIn(@Param("tripId") Long tripId, @Param("statuses") List<ReservationStatus> statuses);

    /** Mark RESERVED/PENDING_PAYMENT reservations past expiresAt as EXPIRED so seat is returned to inventory (audit + consistency). */
    @Modifying
    @Query("update Reservation r set r.status = :expiredStatus, r.updatedAt = :now where r.status in :statuses and r.expiresAt is not null and r.expiresAt < :now")
    int markExpired(@Param("statuses") List<ReservationStatus> statuses, @Param("expiredStatus") ReservationStatus expiredStatus, @Param("now") Instant now);

    long countByStatus(ReservationStatus status);

    /** Admin tickets view: CONFIRMED/PAID reservations with full trip/seat/train (for journey matching). */
    @Query("select r from Reservation r join fetch r.tripSeat ts join fetch ts.seat join fetch ts.trip t join fetch t.train where r.status in :statuses order by r.createdAt desc")
    List<Reservation> findByStatusInOrderByCreatedAtDesc(@Param("statuses") List<ReservationStatus> statuses, org.springframework.data.domain.Pageable pageable);

    void deleteByUserIdIn(List<String> userIds);
}
