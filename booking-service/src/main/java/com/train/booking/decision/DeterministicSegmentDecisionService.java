package com.train.booking.decision;

import com.train.booking.confidence.ConfidenceBand;
import org.springframework.stereotype.Service;

@Service
public class DeterministicSegmentDecisionService implements SegmentDecisionService {
/* This is a simple deterministic decision engine with data-driven logic that can be expanded with real business rules and data inputs. */
    @Override
    public SegmentDecision decide(DecisionContext context) {
        ConfidenceBand band = context.confidenceAssessment().band();
        boolean enforcementSafe = context.dataQualityLevel() == DataQualityLevel.STRONG && band == ConfidenceBand.HIGH;

        if (context.unresolvedMissingEvidence() || context.dataQualityLevel() == DataQualityLevel.WEAK || band == ConfidenceBand.LOW) { // band low = Low
            return new SegmentDecision(SegmentDecisionOutcome.PENDING_RESOLUTION, false, "Weak evidence or unresolved ambiguity");
        }
        if (context.dataQualityLevel() == DataQualityLevel.BORDERLINE || band == ConfidenceBand.MEDIUM) { // band medium = medium
            return new SegmentDecision(SegmentDecisionOutcome.PENDING_REVIEW, false, "Borderline quality or medium confidence");
        }
        if (context.coverageResult() == CoverageResult.FULL && context.routeResult() == RouteValidationResult.VALID) { // coverage full = full
            return new SegmentDecision(SegmentDecisionOutcome.PAID, false, "Full valid entitlement coverage");
        }
        if (context.coverageResult() == CoverageResult.PARTIAL && context.routeResult() != RouteValidationResult.INVALID) { // coverage partial = partial
            return new SegmentDecision(SegmentDecisionOutcome.UNDERPAID, enforcementSafe, "Partial entitlement coverage");
        }
        if (context.coverageResult() == CoverageResult.NONE && context.routeResult() == RouteValidationResult.VALID
            && context.fraudRiskResult() == FraudRiskResult.LOW) {
            return new SegmentDecision(SegmentDecisionOutcome.UNPAID_TRAVEL, enforcementSafe, "Uncovered travel with strong evidence");  // fraud risk low = low
        }
        if (context.coverageResult() == CoverageResult.NONE
            && context.fraudRiskResult() == FraudRiskResult.HIGH
            && context.routeResult() != RouteValidationResult.VALID) {
            return new SegmentDecision(SegmentDecisionOutcome.ESCALATED_FRAUD_REVIEW, false, "High-risk uncovered inconsistent route");
        }
        return new SegmentDecision(SegmentDecisionOutcome.PENDING_REVIEW, false, "Default safe review fallback");
    }
}

