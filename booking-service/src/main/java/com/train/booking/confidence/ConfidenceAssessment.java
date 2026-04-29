package com.train.booking.confidence;

public record ConfidenceAssessment(
    double totalScore,
    ConfidenceBand band,
    ConfidenceBreakdown breakdown,
    boolean autoPenaltyAllowed
) {}

