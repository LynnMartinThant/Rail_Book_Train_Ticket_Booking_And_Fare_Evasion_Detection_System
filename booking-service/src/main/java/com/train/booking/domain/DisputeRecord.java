package com.train.booking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispute_record", indexes = {
    @Index(name = "idx_dispute_segment", columnList = "segment_id"),
    @Index(name = "idx_dispute_user", columnList = "user_id"),
    @Index(name = "idx_dispute_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisputeRecord {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "segment_id", nullable = false)
    private Long segmentId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "evidence_reference", length = 500)
    private String evidenceReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DisputeStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (submittedAt == null) submittedAt = Instant.now();
        if (status == null) status = DisputeStatus.OPEN;
    }
}

