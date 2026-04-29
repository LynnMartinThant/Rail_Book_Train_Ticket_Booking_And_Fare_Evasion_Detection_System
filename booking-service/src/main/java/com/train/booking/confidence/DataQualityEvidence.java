package com.train.booking.confidence;

public record DataQualityEvidence(
    boolean usableForInference,
    boolean usableForEnforcement,
    double trustScore
) {}

