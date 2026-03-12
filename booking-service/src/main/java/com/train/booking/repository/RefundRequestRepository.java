package com.train.booking.repository;

import com.train.booking.domain.RefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    List<RefundRequest> findByUserIdOrderByRequestedAtDesc(String userId);

    List<RefundRequest> findByStatusOrderByRequestedAtDesc(RefundRequest.RefundStatus status);

    Optional<RefundRequest> findByReservationIdAndUserId(Long reservationId, String userId);

    @Query("select r from RefundRequest r order by r.requestedAt desc")
    List<RefundRequest> findAllOrderByRequestedAtDesc(Pageable pageable);
}
