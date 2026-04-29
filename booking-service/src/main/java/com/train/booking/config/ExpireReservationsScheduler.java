package com.train.booking.config;

import com.train.booking.domain.ReservationStatus;
import com.train.booking.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Marks reservations that have passed their expiry time as EXPIRED so seats are
 * explicitly returned to inventory (enterprise checklist: reservation timeout → seat released).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpireReservationsScheduler {

    private final ReservationRepository reservationRepository;

    private static final List<ReservationStatus> EXPIRABLE = List.of(
            ReservationStatus.RESERVED,
            ReservationStatus.PENDING_PAYMENT
    );

    @Scheduled(fixedRateString = "${booking.policy.reservation-expiry-check-ms:60000}") // default every 1 min
    @Transactional
    public void expireReservations() {
        Instant now = Instant.now();
        int updated = reservationRepository.markExpired(EXPIRABLE, ReservationStatus.EXPIRED, now);
        if (updated > 0) {
            log.info("Expired {} reservation(s) past expiry time (seat returned to inventory)", updated);
        }
    }
}
