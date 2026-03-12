package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "reservations", indexes = {
    @Index(name = "idx_reservation_trip_seat", columnList = "trip_seat_id"),
    @Index(name = "idx_reservation_user_status", columnList = "user_id, status"),
    @Index(name = "idx_reservation_expires", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_seat_id", nullable = false)
    private TripSeat tripSeat;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "payment_reference")
    private String paymentReference;

    /** Gateway used: STRIPE, PAYPAL, ADYEN. */
    @Column(name = "payment_gateway", length = 32)
    private String paymentGateway;

    /** Gateway transaction / payment intent ID (server-side verification). */
    @Column(name = "payment_transaction_id", length = 255)
    private String paymentTransactionId;

    /** Currency for payment (e.g. GBP). Verified against gateway amount. */
    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
