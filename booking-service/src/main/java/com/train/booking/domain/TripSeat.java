package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "trip_seats", uniqueConstraints = @UniqueConstraint(columnNames = {"trip_id", "seat_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @OneToOne(mappedBy = "tripSeat", fetch = FetchType.LAZY)
    private Reservation reservation;
}
