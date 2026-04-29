package com.train.booking.confidence;

import java.util.List;

public record ConfidenceBreakdown( // input for G+T+M+R+E/5 - P
    double geofenceScore, //G
    double temporalScore, //T
    double movementCompletenessScore, //M
    double routeAlignmentScore, //R
    double entitlementSupportScore, //E
    double penaltyScore, //P
    List<String> reasons,
    boolean safetyGateTriggered
) {}

