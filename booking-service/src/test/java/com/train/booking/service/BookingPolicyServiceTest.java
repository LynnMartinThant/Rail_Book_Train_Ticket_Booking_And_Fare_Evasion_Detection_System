package com.train.booking.service;

import com.train.booking.config.BookingPolicyProperties;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingPolicyServiceTest {

    private BookingPolicyService service;

    @BeforeEach
    void setUp() {
        BookingPolicyProperties policy = new BookingPolicyProperties(); // criteria for booking policy
        policy.setReservationTimeoutMinutes(15);
        policy.setMaxSeatsPerBooking(10);
        policy.setAllowedReservationStatusesForPayment(List.of("RESERVED", "PENDING_PAYMENT"));
        policy.setAllowedPaymentStatusesForConfirm(List.of("PAID"));
        service = new BookingPolicyService(policy);
    }

    @Test
    void getReservationTimeoutMinutes_returnsConfigured() {
        assertThat(service.getReservationTimeoutMinutes()).isEqualTo(15);
    }

    @Test
    void reservationExpiresAt_isInFuture() {
        assertThat(service.reservationExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void validateSeatCount_acceptsValid() { //seat count
        service.validateSeatCount(1);
        service.validateSeatCount(10);
    }

    @Test
    void validateSeatCount_throwsForZero() { // zero seat
        assertThatThrownBy(() -> service.validateSeatCount(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSeatCount_throwsForOverMax() { // maximun
        assertThatThrownBy(() -> service.validateSeatCount(11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canProceedToPayment_trueForReserved() { //reserved
        assertThat(service.canProceedToPayment(ReservationStatus.RESERVED)).isTrue();
    }

    @Test
    void canProceedToConfirm_trueForPaid() { // statius : paid
        assertThat(service.canProceedToConfirm(ReservationStatus.PAID)).isTrue();
    }

    @Test
    void assertCanPay_passesWhenReservedAndNotExpired() { //status : reserved
        Reservation r = Reservation.builder().status(ReservationStatus.RESERVED).expiresAt(Instant.now().plusSeconds(3600)).build();
        service.assertCanPay(r);
    }

    @Test
    void assertCanPay_throwsWhenExpired() { //expired
        Reservation r = Reservation.builder().status(ReservationStatus.RESERVED).expiresAt(Instant.now().minusSeconds(1)).build();
        assertThatThrownBy(() -> service.assertCanPay(r)).isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");
    }

    @Test
    void assertCanConfirm_passesWhenPaid() { 
        Reservation r = Reservation.builder().status(ReservationStatus.PAID).build();
        service.assertCanConfirm(r);
    }

    @Test
    void assertCanConfirm_throwsWhenReserved() {
        Reservation r = Reservation.builder().status(ReservationStatus.RESERVED).build();
        assertThatThrownBy(() -> service.assertCanConfirm(r)).isInstanceOf(IllegalStateException.class);
    }
}
