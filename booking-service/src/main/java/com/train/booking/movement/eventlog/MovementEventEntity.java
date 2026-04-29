package com.train.booking.movement.eventlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.train.booking.platform.MovementSourceLayer;

import java.time.Instant;

/**
 * Durable movement-domain event log (hybrid ES): append-only record of movement facts and decisions.
 * Ingestion events such as {@code LocationReported} capture transient raw inputs and trigger
 * downstream station/coordination processing, while the system avoids storing a long-lived raw
 * movement history.
 * Kafka is the backbone when enabled; DB remains authoritative for this prototype (replay + audit).
 */
@Entity
@Table(name = "movement_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovementEventEntity {

    /** Deterministic or random UUID string. */
    @Id
    @Column(nullable = false, updatable = false, length = 64)
    private String eventId;

    /** Aggregate key for PassengerMovementAggregate (prototype: userId). */
    @Column(nullable = false, updatable = false, length = 128)
    private String userId;

    /** Cross-event correlation for one ingestion/request chain. */
    @Column(nullable = false, updatable = false, length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 64)
    private MovementEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private MovementSourceLayer sourceLayer;

    /** Event time (device / domain time). */
    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    /** Ingestion time (server time). */
    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    /** JSON payload (event-specific). */
    @Lob
    @Column(nullable = false, updatable = false)
    private String payloadJson;
}

