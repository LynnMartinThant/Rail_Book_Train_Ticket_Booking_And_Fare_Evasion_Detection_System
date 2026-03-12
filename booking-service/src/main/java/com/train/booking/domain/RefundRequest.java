package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refund_requests", indexes = {
    @Index(name = "idx_refund_user", columnList = "user_id"),
    @Index(name = "idx_refund_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "processed_by")
    private String processedBy;

    @PrePersist
    void prePersist() {
        if (requestedAt == null) requestedAt = Instant.now();
    }

    public enum RefundStatus {
        PENDING,
        APPROVED,
        REJECTED
    }
}
