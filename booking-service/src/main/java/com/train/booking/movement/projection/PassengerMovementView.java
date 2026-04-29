package com.train.booking.movement.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Read projection for current passenger movement state (CQRS read model).
 */
@Entity
@Table(name = "passenger_movement_view")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PassengerMovementView {
    @Id
    @Column(nullable = false, updatable = false, length = 128)
    private String userId;

    @Column(length = 128)
    private String currentStation;

    @Column(length = 8)
    private String currentPlatform;

    @Column(length = 16)
    private String lastGeofenceEventType; // ENTERED / EXITED

    @Column(length = 32)
    private String journeyStatus;

    @Column(length = 128)
    private String candidateOriginStation;

    @Column
    private Instant lastEventAt;

    @Column
    private Long lastConfirmedSegmentId;

    @Column(nullable = false)
    private Instant updatedAt;
}

