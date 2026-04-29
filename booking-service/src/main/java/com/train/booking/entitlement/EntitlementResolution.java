package com.train.booking.entitlement;

import com.train.booking.decision.CoverageResult;

import java.util.List;
import java.util.Map;

/**
 * Intermediate entitlement reasoning output (decoupled from enforcement).
 */
public record EntitlementResolution(
    EntitlementState state,
    EntitlementSourceType source,
    CoverageResult coverage,
    boolean temporalValid,
    List<String> reasons,
    Map<String, Object> context
) {}
