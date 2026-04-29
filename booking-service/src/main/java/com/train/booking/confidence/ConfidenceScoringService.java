package com.train.booking.confidence;

public interface ConfidenceScoringService {
    /**
     * Computes evidence reliability and whether automatic enforcement is safe.
     */
    ConfidenceAssessment assess(ConfidenceInput input);
}

