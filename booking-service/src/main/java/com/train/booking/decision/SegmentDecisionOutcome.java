package com.train.booking.decision;

public enum SegmentDecisionOutcome {
    PAID,
    UNDERPAID,
    PENDING_REVIEW,
    PENDING_RESOLUTION,
    UNPAID_TRAVEL,
    ESCALATED_FRAUD_REVIEW,
    OVERTURNED_TO_PAID,
    CLOSED
}

