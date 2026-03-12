package com.train.booking.service;

import com.train.booking.config.BookingRefundProperties;
import com.train.booking.domain.RefundRequest;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.repository.RefundRequestRepository;
import com.train.booking.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RefundService {

    private final ReservationRepository reservationRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final BookingRefundProperties refundProperties;

    /**
     * User requests refund. Allowed only if reservation is PAID and within configured window (e.g. 3 min) of payment.
     */
    @Transactional
    public RefundRequest requestRefund(Long reservationId, String userId) {
        Reservation r = reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        if (r.getStatus() != ReservationStatus.PAID) {
            throw new IllegalStateException("Refund can only be requested for paid reservations");
        }
        Instant paidAt = r.getUpdatedAt();
        long windowSeconds = refundProperties.getRequestWindowMinutes() * 60L;
        if (Instant.now().isAfter(paidAt.plusSeconds(windowSeconds))) {
            throw new IllegalStateException("Refund must be requested within " + refundProperties.getRequestWindowMinutes() + " minutes of payment");
        }
        if (refundRequestRepository.findByReservationIdAndUserId(reservationId, userId)
            .filter(req -> req.getStatus() == RefundRequest.RefundStatus.PENDING)
            .isPresent()) {
            throw new IllegalStateException("Refund already requested for this reservation");
        }
        RefundRequest req = RefundRequest.builder()
            .reservationId(reservationId)
            .userId(userId)
            .status(RefundRequest.RefundStatus.PENDING)
            .build();
        return refundRequestRepository.save(req);
    }

    public List<RefundRequest> findMyRequests(String userId) {
        return refundRequestRepository.findByUserIdOrderByRequestedAtDesc(userId);
    }

    public List<RefundRequest> findAllForAdmin() {
        return refundRequestRepository.findByStatusOrderByRequestedAtDesc(RefundRequest.RefundStatus.PENDING);
    }

    @Transactional
    public RefundRequest approve(Long requestId, String adminId) {
        RefundRequest req = refundRequestRepository.findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Refund request not found"));
        if (req.getStatus() != RefundRequest.RefundStatus.PENDING) {
            throw new IllegalStateException("Request already processed");
        }
        Reservation r = reservationRepository.findByIdAndUserIdWithDetails(req.getReservationId(), req.getUserId())
            .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        r.setStatus(ReservationStatus.REFUNDED);
        r.setUpdatedAt(Instant.now());
        reservationRepository.save(r);
        req.setStatus(RefundRequest.RefundStatus.APPROVED);
        req.setProcessedAt(Instant.now());
        req.setProcessedBy(adminId);
        return refundRequestRepository.save(req);
    }

    @Transactional
    public RefundRequest reject(Long requestId, String adminId) {
        RefundRequest req = refundRequestRepository.findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Refund request not found"));
        if (req.getStatus() != RefundRequest.RefundStatus.PENDING) {
            throw new IllegalStateException("Request already processed");
        }
        req.setStatus(RefundRequest.RefundStatus.REJECTED);
        req.setProcessedAt(Instant.now());
        req.setProcessedBy(adminId);
        return refundRequestRepository.save(req);
    }
}
