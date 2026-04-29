package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A trip segment inferred from geofence events: StationExitDetected(origin, T1) then StationEntryDetected(destination, T2).
 * Idempotency key prevents duplicate processing when events are replayed.
 */
@Entity
@Table(name = "trip_segments", indexes = {
    @Index(name = "idx_trip_segment_passenger", columnList = "passenger_id"),
    @Index(name = "idx_trip_segment_idempotency", columnList = "idempotency_key", unique = true),
    @Index(name = "idx_trip_segment_created", columnList = "created_at"),
    @Index(name = "idx_trip_segment_reservation", columnList = "reservation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @Column(name = "passenger_id", nullable = false)
    private String passengerId;

    @Column(name = "origin_station", nullable = false)
    private String originStation;

    @Column(name = "destination_station", nullable = false)
    private String destinationStation;

    @Column(name = "origin_platform", length = 4)
    private String originPlatform;

    @Column(name = "destination_platform", length = 4)
    private String destinationPlatform;

    @Column(name = "segment_start_time", nullable = false)
    private Instant segmentStartTime;

    @Column(name = "segment_end_time", nullable = false)
    private Instant segmentEndTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "fare_status", nullable = false)
    private FareStatus fareStatus;

    /** Amount already paid for this journey (from ticket); 0 if no ticket. */
    @Column(name = "paid_fare", precision = 10, scale = 2)
    private BigDecimal paidFare;

    /** Additional fare charged when UNDERPAID (actual fare - paid fare). */
    @Column(name = "additional_fare", precision = 10, scale = 2)
    private BigDecimal additionalFare;

    /** Penalty when UNPAID_TRAVEL (e.g. maximum fare for the route). */
    @Column(name = "penalty_amount", precision = 10, scale = 2)
    private BigDecimal penaltyAmount;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment_state")
    private SegmentState segmentState;

    /** When PENDING_RESOLUTION: resolve (buy/upload ticket) by this time to avoid penalty. */
    @Column(name = "resolution_deadline")
    private Instant resolutionDeadline;

    /** When PAID: the reservation (ticket) that covered this segment. Used for ticket-sharing detection. */
    @Column(name = "reservation_id")
    private Long reservationId;

    /** Matched train (trip) from schedule: segment start within ±5 min of trip departure. */
    @Column(name = "matched_trip_id")
    private Long matchedTripId;

    /** Confidence score 0–100 for journey reconstruction (GPS 25%, station 25%, train match 30%, duration 20%). Alerts only if ≥ threshold. */
    @Column(name = "confidence_score", precision = 5, scale = 2)
    private java.math.BigDecimal confidenceScore;

    /**
     * Structured explainability: policy names, rule codes, inputs, confidence breakdown, audit chain.
     * JSON for admin UI and dispute review (versioned document).
     */
    @Lob
    @Column(name = "explanation_json")
    private String explanationJson;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (segmentState == null) segmentState = SegmentState.DETECTED;
    }
}
