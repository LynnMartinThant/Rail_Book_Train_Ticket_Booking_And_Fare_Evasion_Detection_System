package com.train.booking.decision;

import com.train.booking.confidence.ConfidenceAssessment;

public record DecisionContext( // each trip seg
    CoverageResult coverageResult, 
    RouteValidationResult routeResult,
    FraudRiskResult fraudRiskResult,
    DataQualityLevel dataQualityLevel,
    ConfidenceAssessment confidenceAssessment,
    boolean unresolvedMissingEvidence
) {}
