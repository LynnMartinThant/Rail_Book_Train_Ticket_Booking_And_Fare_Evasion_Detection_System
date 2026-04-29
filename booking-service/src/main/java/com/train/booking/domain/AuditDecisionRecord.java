package com.train.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Immutable audit of central policy decisions (fare / fraud) for compliance and admin views.
 */
@Entity
@Table(name = "audit_decision_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditDecisionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String userId;

    @Column
    private Long segmentId;

    /** e.g. FareValidated, FraudDecisionMade */
    @Column(nullable = false, length = 64)
    private String decisionType;

    @Column(nullable = false, length = 64)
    private String correlationId;

    @Column(nullable = false, length = 64)
    private String sourceLayer;

    @Lob
    @Column(nullable = false)
    private String payloadJson;

    @Column(nullable = false)
    private Instant recordedAt;
}
