package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "segment_transition", indexes = {
    @Index(name = "idx_segment_transition_segment", columnList = "segment_id"),
    @Index(name = "idx_segment_transition_occurred", columnList = "occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SegmentTransition {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "segment_id", nullable = false)
    private Long segmentId;

    @Column(name = "from_state")
    private String fromState;

    @Column(name = "to_state", nullable = false)
    private String toState;

    @Column(name = "trigger_event_type", nullable = false)
    private String triggerEventType;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id")
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (occurredAt == null) occurredAt = Instant.now();
    }
}

