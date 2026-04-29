package com.train.booking.entitlement;

/**
 * State-based entitlement result used between journey reconstruction and decisions.
 */
public enum EntitlementState {
    COVERED,
    NOT_COVERED,
    UNVERIFIED
}
