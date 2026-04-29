package com.train.booking.quality;

import java.util.List;

public record DataQualityAssessment(
    boolean usableForInference,
    boolean usableForEnforcement,
    double trustScore,
    List<String> issues
) {}

