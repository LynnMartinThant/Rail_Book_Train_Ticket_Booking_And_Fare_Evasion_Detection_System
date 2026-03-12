package com.train.booking.repository;

import com.train.booking.domain.StationEntryAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface StationEntryActionRepository extends JpaRepository<StationEntryAction, Long> {

    List<StationEntryAction> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, StationEntryAction.Status status);

    Optional<StationEntryAction> findByIdAndUserId(Long id, String userId);

    /** Idempotency: avoid duplicate PENDING_OPTION for same user+geofence under concurrent traffic. */
    boolean existsByUserIdAndGeofenceIdAndStatus(String userId, Long geofenceId, StationEntryAction.Status status);

    /** Lock row for atomic update (validate QR); prevents double completion. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StationEntryAction a where a.id = :id and a.userId = :userId")
    Optional<StationEntryAction> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") String userId);

    /** Prevent same reservation being used for two different actions. */
    boolean existsByQrValidatedReservationId(Long reservationId);
}
