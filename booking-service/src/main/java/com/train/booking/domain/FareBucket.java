package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Fare tier (bucket) for a trip. Tiers are applied in display order: when seats sold exceed
 * a tier's allocation, the next tier is used. -1 seatsAllocated means unlimited.
 */
@Entity
@Table(name = "fare_buckets", indexes = @Index(name = "idx_fare_bucket_trip", columnList = "trip_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FareBucket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /** Tier name: ADVANCE_1, ADVANCE_2, ADVANCE_3, OFF_PEAK, ANYTIME */
    @Column(name = "tier_name", nullable = false, length = 32)
    private String tierName;

    /** Max seats for this tier; -1 = unlimited */
    @Column(name = "seats_allocated", nullable = false)
    private int seatsAllocated;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** Order of application (1 = first tier to sell from) */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
