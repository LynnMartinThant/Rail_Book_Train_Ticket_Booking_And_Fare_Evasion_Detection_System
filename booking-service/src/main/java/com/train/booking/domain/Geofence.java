package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A geographic zone (e.g. station). Used to detect entry/exit and trigger alerts / fare evasion audit.
 */
@Entity
@Table(name = "geofences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Geofence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(insertable = false, updatable = false)
    private Long id;

    /** Display name (e.g. "Sheffield Station") */
    @Column(nullable = false)
    private String name;

    /** Station name used to match Trip.fromStation / toStation (e.g. "Sheffield") */
    @Column(name = "station_name", nullable = false)
    private String stationName;

    /** Platform at this station (e.g. 1A, 3B). Five main platforms each with A and B: 1A–5B. */
    @Column(length = 4)
    private String platform;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** Radius in metres */
    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters;
}
