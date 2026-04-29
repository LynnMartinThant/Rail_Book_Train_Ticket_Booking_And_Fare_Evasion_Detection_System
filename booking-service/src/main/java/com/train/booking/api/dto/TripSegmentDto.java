package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class TripSegmentDto {
    private Long id;
    private String passengerId;
    /** Alias for quick admin scan views. */
    private String origin;
    /** Alias for quick admin scan views. */
    private String destination;
    private String originStation;
    private String destinationStation;
    private String originPlatform;
    private String destinationPlatform;
    private Instant segmentStartTime;
    private Instant segmentEndTime;
    private String fareStatus; // PAID, UNDERPAID, PENDING_RESOLUTION, UNPAID_TRAVEL
    private Instant resolutionDeadline;
    private BigDecimal paidFare;
    private BigDecimal additionalFare;
    private BigDecimal penaltyAmount;
    private Instant createdAt;
    /** Matched train (trip) ID from schedule (±5 min). */
    private Long matchedTripId;
    /** Confidence score 0–100; alerts only if ≥ threshold (e.g. 85). */
    private BigDecimal confidenceScore;
    /** Parsed {@code TripSegment.explanationJson} for API consumers. */
    private Map<String, Object> explanation;
    /** Lifecycle state for deterministic state-machine progression. */
    private String segmentState;
    /** Derived confidence band (HIGH/MEDIUM/LOW) from computed contract. */
    private String confidenceBand;
    /** Human-readable uncertainty and evidence reasons. */
    private List<String> confidenceReasons;
    /** State transition history for this segment. */
    private List<SegmentTransitionDto> transitions;
    /** Formal disputes opened for this segment. */
    private List<DisputeRecordDto> disputes;
    /** Recomputed decision lineage records for this segment. */
    private List<RecomputationRecordDto> recomputations;
    /** Top-level trace reference for event/evidence drill-down. */
    private String traceReference;
    /** Explicit decision output for dashboard cards. */
    private String decisionOutcome;
    /** Explicitly states decision source. */
    private String decisionBasis;
    /** Data quality score affecting inference, when available. */
    private Double dataQualityScore;
    /** Data quality flags or inferred reasons from confidence breakdown. */
    private List<String> qualityIssues;
    /** Whether quality constraints limited inference or enforcement confidence. */
    private Boolean inferenceLimitedByQuality;
    /** Human-readable summary of quality impact. */
    private String qualityImpact;
    /** Ticket-vs-segment coverage object extracted for explainability. */
    private Map<String, Object> coverageCheck;
    /** Quick lineage indicator for disputes/recomputation. */
    private Boolean hasDisputeLineage;
}
