package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @Column(nullable = false)
    private String fromStation;

    @Column(nullable = false)
    private String toStation;

    @Column(nullable = false)
    private Instant departureTime;

    // Platform at fromStation (e.g. 1A, 2B).
    @Column(length = 4)
    private String platform;

    @Column(nullable = false)
    private BigDecimal pricePerSeat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TripSeat> tripSeats = new ArrayList<>();
}
