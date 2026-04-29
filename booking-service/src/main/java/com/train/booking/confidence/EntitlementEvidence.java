package com.train.booking.confidence;

public record EntitlementEvidence(
    boolean ticketExists,
    boolean passengerMatches,
    boolean fullCoverage,
    boolean partialCoverage
) {}

