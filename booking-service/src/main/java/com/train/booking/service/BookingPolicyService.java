package com.train.booking.service;

import com.train.booking.config.BookingPolicyProperties;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Policy-driven checks for booking workflow.
 */
@Service
@RequiredArgsConstructor
public class BookingPolicyService {

    private final BookingPolicyProperties policy;

    public int getReservationTimeoutMinutes() {
        return policy.getReservationTimeoutMinutes();
    }

    public Instant reservationExpiresAt() {
        return Instant.now().plusSeconds(policy.getReservationTimeoutMinutes() * 60L);
    }

    public void validateSeatCount(int seatCount) {
        if (seatCount <= 0 || seatCount > policy.getMaxSeatsPerBooking()) {
            throw new IllegalArgumentException(
                "Seat count must be between 1 and " + policy.getMaxSeatsPerBooking());
        }
    }

    public boolean canProceedToPayment(ReservationStatus status) {
        return policy.getAllowedReservationStatusesForPayment().stream()
            .anyMatch(s -> s.equals(status.name()));
    }

    public boolean canProceedToConfirm(ReservationStatus status) {
        return policy.getAllowedPaymentStatusesForConfirm().stream()
            .anyMatch(s -> s.equals(status.name()));
    }

    public void assertCanPay(Reservation r) {
        if (r.getExpiresAt() != null && r.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalStateException("Reservation has expired");
        }
        if (!canProceedToPayment(r.getStatus())) {
            throw new IllegalStateException("Reservation cannot be paid; status: " + r.getStatus());
        }
    }

    public void assertCanConfirm(Reservation r) {
        if (!canProceedToConfirm(r.getStatus())) {
            throw new IllegalStateException("Booking cannot be confirmed; status: " + r.getStatus());
        }
    }
}
