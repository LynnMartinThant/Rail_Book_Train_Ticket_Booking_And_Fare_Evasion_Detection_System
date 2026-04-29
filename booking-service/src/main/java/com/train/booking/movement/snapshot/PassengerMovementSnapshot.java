package com.train.booking.movement.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "passenger_movement_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PassengerMovementSnapshot {

    @Id
    @Column(nullable = false, updatable = false, length = 128)
    private String userId;

    @Column(nullable = false, length = 64)
    private String lastEventId;

    @Column(nullable = false)
    private long eventCount;

    @Lob
    @Column(nullable = false)
    private String stateJson;

    @Column(nullable = false)
    private Instant snapshotAt;
}

