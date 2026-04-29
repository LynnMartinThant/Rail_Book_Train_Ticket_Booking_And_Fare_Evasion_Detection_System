package com.train.booking.quality;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class DefaultDataQualityScoringService implements DataQualityScoringService {

    @Override
    public DataQualityAssessment assess(Double accuracyMeters, Duration lag, double distanceMetres, boolean duplicateOrConflict) {
        List<String> issues = new ArrayList<>();
        double trust = 100.0;

        if (accuracyMeters != null && accuracyMeters > 50.0) {
            trust -= 15.0;
            issues.add("Poor GPS accuracy");
        }
        if (lag != null && lag.getSeconds() > 30) {
            trust -= 10.0;
            issues.add("Stale report lag > 30s");
        }
        if (distanceMetres > 5_000.0) {
            trust -= 25.0;
            issues.add("Implausible jump");
        }
        if (duplicateOrConflict) {
            trust -= 10.0;
            issues.add("Duplicate/conflicting report");
        }

        trust = Math.max(0.0, Math.min(100.0, trust));
        boolean usableForEnforcement = trust >= 70.0;
        boolean usableForInference = trust >= 40.0;
        return new DataQualityAssessment(usableForInference, usableForEnforcement, trust, issues);
    }
}

