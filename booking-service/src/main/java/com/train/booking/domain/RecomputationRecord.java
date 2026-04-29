package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recomputation_record", indexes = {
    @Index(name = "idx_recompute_segment", columnList = "segment_id"),
    @Index(name = "idx_recompute_dispute", columnList = "dispute_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecomputationRecord {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "segment_id", nullable = false)
    private Long segmentId;

    @Column(name = "dispute_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID disputeId;

    @Column(name = "previous_decision", nullable = false)
    private String previousDecision;

    @Column(name = "recomputed_decision", nullable = false)
    private String recomputedDecision;

    @Lob
    @Column(name = "explanation_json")
    private String explanationJson;

    @Column(name = "recomputed_confidence")
    private Double recomputedConfidence;

    @Column(name = "recomputed_at", nullable = false)
    private Instant recomputedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (recomputedAt == null) recomputedAt = Instant.now();
    }
}

