package com.train.booking.service;

import com.train.booking.domain.*;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.repository.TripSeatRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core booking workflow: Reserve → Payment → Confirm.
 * Uses pessimistic locking on TripSeat to prevent double booking (ACID).
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private final TripSeatRepository tripSeatRepository;
    private final ReservationRepository reservationRepository;
    private final TripRepository tripRepository;
    private final PricingService pricingService;
    private final BookingPolicyService policyService;

    /**
     * Reserve seat(s). Locks TripSeat rows to prevent double booking.
     * @return Created reservations (one per seat)
     */
    @Transactional
    public List<Reservation> reserve(String userId, Long tripId, List<Long> seatIds, String journeyFromStation, String journeyToStation) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("At least one seat is required");
        }
        policyService.validateSeatCount(seatIds.size());

        Instant now = Instant.now();
        Trip trip = tripRepository.findByIdWithTrain(tripId)
            .orElseThrow(() -> new IllegalArgumentException("Trip not found: " + tripId));
        BigDecimal amount = pricingService.getPrice(trip, now).getPrice();

        Instant expiresAt = policyService.reservationExpiresAt();
        List<Reservation> created = new ArrayList<>();

        for (Long seatId : seatIds) {
            TripSeat tripSeat = tripSeatRepository.findByTripIdAndSeatIdForUpdate(tripId, seatId)
                .orElseThrow(() -> new IllegalArgumentException("Trip or seat not found: tripId=" + tripId + ", seatId=" + seatId));

            reservationRepository.findActiveByTripSeatId(
                tripSeat.getId(),
                List.of(ReservationStatus.RESERVED, ReservationStatus.PENDING_PAYMENT, ReservationStatus.PAYMENT_PROCESSING, ReservationStatus.PAID, ReservationStatus.CONFIRMED),
                Instant.now()
            ).ifPresent(r -> {
                throw new IllegalStateException("Seat " + tripSeat.getSeat().getSeatNumber() + " is already booked or reserved");
            });

            // Reuse any reservation that is no longer active (expired by time or status)
            Reservation reservation = reservationRepository
                .findByTripSeatIdOrderByIdDesc(tripSeat.getId(), PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .filter(r -> isReusableForRebook(r, now))
                .orElse(null);

            if (reservation != null) {
                reservation.setUserId(userId);
                reservation.setStatus(ReservationStatus.RESERVED);
                reservation.setAmount(amount);
                reservation.setExpiresAt(expiresAt);
                reservation.setPaymentReference(null);
                reservation.setJourneyFromStation(journeyFromStation);
                reservation.setJourneyToStation(journeyToStation);
                reservation.setUpdatedAt(now);
                created.add(reservationRepository.save(reservation));
            } else {
                reservation = Reservation.builder()
                    .tripSeat(tripSeat)
                    .userId(userId)
                    .status(ReservationStatus.RESERVED)
                    .amount(amount)
                    .expiresAt(expiresAt)
                    .journeyFromStation(journeyFromStation)
                    .journeyToStation(journeyToStation)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
                created.add(reservationRepository.save(reservation));
            }
        }
        return created;
    }

    /** True if this reservation row can be reused (not active: expired, refunded, or cancelled). */
    private static boolean isReusableForRebook(Reservation r, Instant now) {
        if (r.getStatus() == ReservationStatus.EXPIRED || r.getStatus() == ReservationStatus.REFUNDED || r.getStatus() == ReservationStatus.CANCELLED) return true;
        if (r.getExpiresAt() != null && !r.getExpiresAt().isAfter(now)) return true;
        return false;
    }



    
    @Transactional
    public Reservation payment(Long reservationId, String userId, String paymentReference) { //  Auto-generates payment ID (dd/mm/yy-NNNN) if not provided.
        Reservation r = reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        policyService.assertCanPay(r);
        String ref = Optional.ofNullable(paymentReference)
            .filter(s -> !s.isBlank())
            .orElseGet(() -> "pay-" + UUID.randomUUID());
        r.setStatus(ReservationStatus.PAID);
        r.setPaymentReference(ref); // ref
        r.setUpdatedAt(Instant.now());
        return reservationRepository.save(r);
    }

   // confirm
    @Transactional
    public Reservation confirm(Long reservationId, String userId) {
        Reservation r = reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        policyService.assertCanConfirm(r);
        r.setStatus(ReservationStatus.CONFIRMED);
        r.setUpdatedAt(Instant.now());
        return reservationRepository.save(r);
    }

// cancel
    @Transactional
    public void cancel(Long reservationId, String userId) {
        Reservation r = reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        r.setStatus(ReservationStatus.EXPIRED);
        r.setUpdatedAt(Instant.now());
        reservationRepository.save(r);
    }
}
