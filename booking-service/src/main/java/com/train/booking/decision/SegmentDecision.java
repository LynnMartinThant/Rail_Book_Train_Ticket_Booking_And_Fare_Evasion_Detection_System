package com.train.booking.decision;

public record SegmentDecision(
    SegmentDecisionOutcome outcome,
    boolean punitiveAllowed,
    String reason
) {}

