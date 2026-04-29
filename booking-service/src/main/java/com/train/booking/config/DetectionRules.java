package com.train.booking.config;

/**
 * Named detection rules used by the journey reconstruction and fraud engine.
 * These are logged to the audit trail and used to prevent GPS errors and enforce policy.
 */
public final class DetectionRules {

    private DetectionRules() {}

    /** Passenger with no valid ticket for the detected journey (geofence entry + segment). */
    public static final String NO_TICKET = "NO_TICKET";

    /** Traveling beyond ticket destination (short ticket → UNDERPAID). */
    public static final String OVER_TRAVEL = "OVER_TRAVEL";

    /** Wrong train route: segment not on ticket route or stations not on configured route. */
    public static final String ROUTE_VIOLATION = "ROUTE_VIOLATION";

    /** Multiple users using one ticket (same reservation, different passengers, overlapping times). */
    public static final String TICKET_SHARING = "TICKET_SHARING";

    /** Abnormal travel behavior (e.g. suspicious account, impossible journey). */
    public static final String SUSPICIOUS_PATTERN = "SUSPICIOUS_PATTERN";

    /** Low confidence: do not trigger penalty/notification to prevent GPS errors. */
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
}
