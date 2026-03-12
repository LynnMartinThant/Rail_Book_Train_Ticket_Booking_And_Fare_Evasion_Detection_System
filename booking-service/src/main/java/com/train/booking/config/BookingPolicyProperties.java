package com.train.booking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "booking.policy")
public class BookingPolicyProperties {

    /** Reservation expires after this many minutes if not paid. */
    private int reservationTimeoutMinutes = 15;

    /** Maximum seats allowed in a single booking. */
    private int maxSeatsPerBooking = 10;

    /** Statuses that allow proceeding to payment (manual or gateway). */
    private List<String> allowedReservationStatusesForPayment = List.of("RESERVED", "PENDING_PAYMENT");

    /** Statuses that allow proceeding to confirm. */
    private List<String> allowedPaymentStatusesForConfirm = List.of("PAID");
}
