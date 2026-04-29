package com.train.booking.confidence;

public record ConfidenceInput( // input for 
    MovementEvidence movementEvidence,
    RouteEvidence routeEvidence,
    EntitlementEvidence entitlementEvidence,
    DataQualityEvidence dataQualityEvidence,
    AnomalyEvidence anomalyEvidence
) {
    public ConfidenceInput {
        if (movementEvidence == null || routeEvidence == null || entitlementEvidence == null
            || dataQualityEvidence == null || anomalyEvidence == null) {
            throw new IllegalArgumentException("ConfidenceInput requires all evidence fields.");
        }
    }
}

