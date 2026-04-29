package com.train.booking.quality;

import java.time.Duration;

public interface DataQualityScoringService {
    DataQualityAssessment assess(Double accuracyMeters, Duration lag, double distanceMetres, boolean duplicateOrConflict);
}

