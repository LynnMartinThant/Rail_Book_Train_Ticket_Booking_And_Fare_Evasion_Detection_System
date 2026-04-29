package com.train.booking.confidence;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DefaultConfidenceScoringService implements ConfidenceScoringService {

    private static final double MAX_SCORE_WHEN_UNSAFE_FOR_AUTO_PENALTY = 59.0;

    @Override
    public ConfidenceAssessment assess(ConfidenceInput input) {
        MovementEvidence movement = input.movementEvidence();
        RouteEvidence route = input.routeEvidence();
        EntitlementEvidence entitlement = input.entitlementEvidence();
        DataQualityEvidence quality = input.dataQualityEvidence();
        AnomalyEvidence anomalies = input.anomalyEvidence();

        List<String> reasons = new ArrayList<>();
        double geofenceScore = geofenceScore(movement, route, reasons);
        double temporalScore = temporalScore(quality, reasons);
        double movementCompletenessScore = movementCompletenessScore(movement, reasons);
        double routeAlignmentScore = routeAlignmentScore(route, reasons);
        double entitlementSupportScore = entitlementScore(entitlement, reasons);
        double penaltyScore = penaltyScore(movement, quality, anomalies, reasons);

        // Simple model with explicit evidence factors: GPS quality, movement completeness, route alignment, entitlement support.
        double total = 0.30 * geofenceScore
            + 0.20 * movementCompletenessScore
            + 0.25 * routeAlignmentScore
            + 0.25 * entitlementSupportScore
            - penaltyScore;

        boolean safetyGateTriggered = isUnsafeForAutomaticPenalty(movement, quality, anomalies, reasons);
        if (safetyGateTriggered) {
            total = Math.min(total, MAX_SCORE_WHEN_UNSAFE_FOR_AUTO_PENALTY);
            reasons.add("Safety gate triggered: automatic penalty blocked; manual review required.");
        }
        total = clamp(total);

        ConfidenceBand band = total >= 85.0
            ? ConfidenceBand.HIGH
            : total >= 60.0 ? ConfidenceBand.MEDIUM : ConfidenceBand.LOW;

        ConfidenceBreakdown breakdown = new ConfidenceBreakdown(
            geofenceScore,
            temporalScore,
            movementCompletenessScore,
            routeAlignmentScore,
            entitlementSupportScore,
            penaltyScore,
            reasons,
            safetyGateTriggered
        );
        return new ConfidenceAssessment(total, band, breakdown, !safetyGateTriggered);
    }

    private double temporalScore(DataQualityEvidence quality, List<String> reasons) {
        if (!quality.usableForInference()) {
            reasons.add("Temporal reliability fallback because data quality is weak.");
            return 25.0;
        }
        return clamp(quality.trustScore());
    }

    private double routeAlignmentScore(RouteEvidence route, List<String> reasons) {
        if (route.hasUnexplainedJump()) {
            reasons.add("Unexplained route jump");
            return 20.0;
        }
        if (route.routeAligned() && route.stationOrderValid()) {
            return 100.0;
        }
        reasons.add("Route mismatch or invalid station order");
        return 40.0;
    }

    private double geofenceScore(MovementEvidence movement, RouteEvidence route, List<String> reasons) {
        double score = 100.0;
        if (movement.gpsAccuracyMeters() != null && movement.gpsAccuracyMeters() > 50.0) {
            score -= 20.0;
            reasons.add("Poor GPS/geofence certainty (>50m)");
        }
        if (!route.stationOrderValid()) {
            score -= 20.0;
            reasons.add("Station order ambiguity");
        }
        return clamp(score);
    }

    private double movementCompletenessScore(MovementEvidence movement, List<String> reasons) {
        double score = 0.0;
        score += movement.entryPresent() ? 50.0 : 0.0;
        score += movement.exitPresent() ? 30.0 : 0.0;
        if (movement.sampleCount() >= 3) {
            score += 20.0;
        } else if (movement.sampleCount() >= 1) {
            score += 10.0;
            reasons.add("Sparse movement sampling");
        } else {
            reasons.add("No movement samples");
        }
        return clamp(score);
    }

    private double entitlementScore(EntitlementEvidence entitlement, List<String> reasons) {
        if (!entitlement.ticketExists()) {
            reasons.add("No entitlement evidence");
            return 0.0;
        }
        if (!entitlement.passengerMatches()) {
            reasons.add("Entitlement passenger mismatch");
            return 10.0;
        }
        if (entitlement.fullCoverage()) return 100.0;
        if (entitlement.partialCoverage()) {
            reasons.add("Partial entitlement coverage");
            return 60.0;
        }
        reasons.add("Entitlement does not cover observed segment");
        return 20.0;
    }

    private double penaltyScore(
        MovementEvidence movement,
        DataQualityEvidence quality,
        AnomalyEvidence anomalies,
        List<String> reasons
    ) {
        double penalty = 0.0;
        if (movement.gpsAccuracyMeters() != null && movement.gpsAccuracyMeters() > 50.0) {
            penalty += 15.0;
            reasons.add("Penalty: low GPS reliability (-15)");
        }
        if (anomalies.missingEntry()) {
            penalty += 20.0;
            reasons.add("Penalty: missing entry evidence (-20)");
        }
        if (anomalies.missingExit()) {
            penalty += 15.0;
            reasons.add("Penalty: missing exit evidence (-15)");
        }
        if (anomalies.outOfOrderEvents()) {
            penalty += 10.0;
            reasons.add("Penalty: out-of-order evidence (-10)");
        }
        if (anomalies.sparseReporting()) {
            penalty += 10.0;
            reasons.add("Penalty: sparse reporting (-10)");
        }
        if (anomalies.implausibleJump()) {
            penalty += 25.0;
            reasons.add("Penalty: implausible movement jump (-25)");
        }
        if (!quality.usableForEnforcement()) {
            penalty += 8.0;
            reasons.add("Penalty: data quality not enforcement-safe (-8)");
        }
        return penalty;
    }

    private boolean isUnsafeForAutomaticPenalty(
        MovementEvidence movement,
        DataQualityEvidence quality,
        AnomalyEvidence anomalies,
        List<String> reasons
    ) {
        boolean unsafe = false;
        if (!quality.usableForEnforcement()) {
            unsafe = true;
            reasons.add("Unsafe for auto-penalty: quality not enforcement-safe.");
        }
        if (!movement.entryPresent() || !movement.exitPresent()) {
            unsafe = true;
            reasons.add("Unsafe for auto-penalty: incomplete movement evidence.");
        }
        if (anomalies.outOfOrderEvents() || anomalies.implausibleJump()) {
            unsafe = true;
            reasons.add("Unsafe for auto-penalty: anomaly pattern requires human review.");
        }
        return unsafe;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}